package com.sharedsync.shared.listener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.history.HistoryService;
import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PresenceSessionManager {

    private static final String DEFAULT_INDEX = "0";
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_DELETE = "delete";

    private final PresenceStorage presenceStorage;
    private final PresenceBroadcaster presenceBroadcaster;
    private final UserProvider userProvider;        
    private final CacheInitializer cacheInitializer;
    private final CacheSyncService cacheSyncService;
    private final HistoryService historyService;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncAuthProperties authProperties;
    private final SharedSyncPresenceProperties presenceProperties;

    // 현재 서버 인스턴스에서 관리 중인 세션 목록
    private final java.util.Set<String> localSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 연결 시 (입장)
     */
    public void handleSubscribe(String rootId, String userId, String sessionId) {
        log.info("[PresenceManager] handleSubscribe: rootId={}, userId={}, sessionId={}", rootId, userId, sessionId);
        
        if (!authProperties.isEnabled()) {
            userId = "ws-" + sessionId;
        }

        localSessions.add(sessionId);

        if (!presenceStorage.hasTracker(rootId)) {
            log.info("[PresenceManager] Initializing hierarchy for rootId={}", rootId);
            cacheInitializer.initializeHierarchy(rootId);
        }

        Map<String, Object> userInfo = presenceStorage.getUserInfoByUserId(userId);
        if (userInfo == null || userInfo.isEmpty()) {
            log.debug("[PresenceManager] UserInfo not found in cache for userId={}, fetching from provider", userId);
            userInfo = userProvider.findUserInfoByUserId(userId);
            if (userInfo != null && !userInfo.isEmpty()) {
                presenceStorage.saveUserInfo(userId, userInfo);
            }
        }

        presenceStorage.insertTracker(rootId, sessionId, userId, DEFAULT_INDEX);
        presenceStorage.mapSessionToRoot(sessionId, rootId, presenceProperties.getSessionTimeout());
        presenceStorage.addActiveSession(userId, sessionId);
        
        final String finalUserId = userId;
        broadcastUpdate(rootId, ACTION_CREATE, userId);


        CompletableFuture.delayedExecutor(presenceProperties.getBroadcastDelay(), TimeUnit.MILLISECONDS).execute(() -> {
            try {
                log.debug("[PresenceManager] Sending initial message to session: sessionId={}", sessionId);
                sendToSession(rootId, sessionId, ACTION_CREATE, finalUserId);
            } catch (Exception e) {
                log.warn("Failed to send initial presence message", e);
            }
        });
    }


    /**
     * 하트비트 처리 (세션 만료 연장)
     */
    public void handleHeartbeat(String sessionId) {
        presenceStorage.refreshSession(sessionId, presenceProperties.getSessionTimeout());
    }

    /**
     * 주기적으로 모든 방의 좀비 데이터를 정리합니다.
     * 클라이언트가 목록을 요청하지 않더라도 데이터 무결성을 유지하기 위함입니다.
     */
    @Scheduled(fixedDelayString = "${sharedsync.presence.cleanup-interval:30}000")
    public void scheduleCleanup() {
        if (!presenceProperties.isEnabled() || presenceProperties.getCleanupInterval() <= 0) {
            return;
        }

        log.debug("Starting periodic presence zombie data cleanup...");
        java.util.Set<String> allRooms = presenceStorage.getAllRoomIds();
        for (String rootId : allRooms) {
            try {
                List<String> removedEntries = presenceStorage.purgeZombies(rootId);
                for (String entry : removedEntries) {
                    String[] parts = entry.split("//");
                    if (parts.length < 2) continue;
                    String userId = parts[0];
                    String sessionId = parts[1];
                    log.info("ZOMBIE DETECTED: Removing user {} from room {}", userId, rootId);
                    
                    performDisconnect(rootId, userId, sessionId);
                }
            } catch (Exception e) {
                log.warn("Failed to cleanup room {}: {}", rootId, e.getMessage());
            }
        }
    }

    private void syncToDatabaseIfLocked(String rootId) {
        if (presenceStorage.acquireSyncLock(rootId)) {
            try {
                if (!presenceStorage.hasTracker(rootId)) {
                    cacheSyncService.syncToDatabase(rootId);
                }
            } finally {
                presenceStorage.releaseSyncLock(rootId);
            }
        }
    }

    private void broadcastUpdate(String rootId, String action, String userId) {
        String channel = presenceRootResolver.getChannel();
        Map<String, Object> userInfo = presenceStorage.getUserInfoByUserId(userId);

        // 중복 userId 제거 후 리스트 생성
        java.util.List<Map<String, Object>> userList = buildUserListWithoutDuplicates(rootId);

        presenceBroadcaster.broadcast(
            channel,
            rootId,
            action,
            userId,
            userInfo,
            userList
        );
    }

    private void sendToSession(String rootId, String sessionId, String action, String userId) {
        String channel = presenceRootResolver.getChannel();
        Map<String, Object> userInfo = presenceStorage.getUserInfoByUserId(userId);

        // 인증된 사용자라면 "u:ID", 아니라면 세션 ID 등을 타겟으로 합니다.
        String principalName = authProperties.isEnabled() ? "u:" + userId : sessionId;

        // 중복 userId 제거 후 리스트 생성
        java.util.List<Map<String, Object>> userList = buildUserListWithoutDuplicates(rootId);

        presenceBroadcaster.sendToSession(
            channel,
            rootId,
            principalName,
            sessionId,
            action,
            userId,
            userInfo,
            userList
        );
    }

    /**
     * 연결 해제 시 (퇴장)
     */
    public void handleDisconnect(String userId, String sessionId) {
        String rootId = presenceStorage.removeSessionRootMapping(sessionId);
        performDisconnect(rootId, userId, sessionId);
    }

    private void performDisconnect(String rootId, String userId, String sessionId) {
        localSessions.remove(sessionId);

        if (rootId == null || rootId.isBlank()) return;

        if (!authProperties.isEnabled()) {
            userId = "ws-" + sessionId;
        }

        if (userId == null) {
            // 트래커에서 해당 세션에 매핑된 userId 찾기
            Map<String, String> entries = presenceStorage.getTrackerEntries(rootId);
            for (String key : entries.keySet()) {
                if (key.endsWith("//" + sessionId)) {
                    userId = key.split("//")[0];
                    break;
                }
            }
        }

        if (userId == null) return;

        presenceStorage.removeTracker(rootId, sessionId, userId);
        presenceStorage.removeActiveSession(userId, sessionId);
        historyService.clearHistory(rootId, sessionId);

        if (!presenceStorage.hasTracker(rootId)) {
            syncToDatabaseIfLocked(rootId);
        }

        broadcastUpdate(rootId, ACTION_DELETE, userId);

        // 다른 방이나 다른 세션에 여전히 남아있는지 확인 후 삭제
        if (!presenceStorage.isUserActiveAnywhere(userId)) {
            presenceStorage.removeUserInfo(userId);
        }
    }

    // 중복 userId를 제거한 userList 생성
    private List<Map<String, Object>> buildUserListWithoutDuplicates(String rootId) {
        java.util.Set<String> uniqueUserIds = new java.util.LinkedHashSet<>(presenceStorage.getUserIdsInRoom(rootId));
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (String id : uniqueUserIds) {
            Map<String, Object> userInfo = presenceStorage.getUserInfoByUserId(id);
            Map<String, Object> userMap = new java.util.HashMap<>();
            userMap.put("uid", id);
            userMap.put("userInfo", userInfo != null ? userInfo : new java.util.HashMap<>());
            result.add(userMap);
        }
        return result;
    }

    /**
     * 서버 종료 시 관리 중인 세션들을 정리하여 좀비 데이터 방지
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up {} local presence sessions before shutdown...", localSessions.size());
        for (String sessionId : localSessions) {
            try {
                handleDisconnect(null, sessionId);
            } catch (Exception e) {
                log.warn("Failed to cleanup session {} during shutdown: {}", sessionId, e.getMessage());
            }
        }
        localSessions.clear();
    }
}