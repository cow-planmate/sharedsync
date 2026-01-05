package com.sharedsync.shared.listener;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PresenceSessionManager {

    private static final String DEFAULT_INDEX = "0";
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_DELETE = "delete";

    private final PresenceStorage presenceStorage;
    private final PresenceBroadcaster presenceBroadcaster;
    private final UserProvider userProvider;        
    private final CacheInitializer cacheInitializer;
    private final CacheSyncService cacheSyncService;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncAuthProperties authProperties;

    /**
     * 연결 시 (입장)
     */
    public void handleSubscribe(String rootId, String userId, String sessionId) {
        if (!authProperties.isEnabled()) {
            userId = "ws-" + sessionId;
        }

        if (!presenceStorage.hasTracker(rootId)) {
            cacheInitializer.initializeHierarchy(rootId);
        }

        String nickname = presenceStorage.getNicknameByUserId(userId);
        if (nickname == null || nickname.isBlank()) {
            nickname = userProvider.findNicknameByUserId(userId);
            if (nickname != null) presenceStorage.saveUserNickname(userId, nickname);
        }

        presenceStorage.insertTracker(rootId, sessionId, userId, DEFAULT_INDEX);
        presenceStorage.mapSessionToRoot(sessionId, rootId);
        presenceStorage.addActiveSession(userId, sessionId);

        String channel = presenceRootResolver.getChannel();

        String finalNickname = nickname;
        presenceBroadcaster.broadcast(
                channel,
                rootId,
                ACTION_CREATE,
                userId,
                finalNickname,
                buildUserList(rootId)
        );
    }


    /**
     * 연결 해제 시 (퇴장)
     */
    public void handleDisconnect(String userId, String sessionId) {
        String rootId = presenceStorage.removeSessionRootMapping(sessionId);
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

        if (!presenceStorage.hasTracker(rootId)) {
            if (presenceStorage.acquireSyncLock(rootId)) {
                try {
                    // 락 획득 후 다시 한번 확인 (그 사이에 누가 들어왔을 수 있음)
                    if (!presenceStorage.hasTracker(rootId)) {
                        cacheSyncService.syncToDatabase(rootId);
                    }
                } finally {
                    presenceStorage.releaseSyncLock(rootId);
                }
            }
        }

        String channel = presenceRootResolver.getChannel();
        String nickname = presenceStorage.getNicknameByUserId(userId);

        presenceBroadcaster.broadcast(
                channel,
                rootId,
                ACTION_DELETE,
                userId,
                nickname,
                buildUserList(rootId)
        );

        // 다른 방이나 다른 세션에 여전히 남아있는지 확인 후 삭제
        if (!presenceStorage.isUserActiveAnywhere(userId)) {
            presenceStorage.removeUserNickname(userId);
        }
    }

    private List<Map<String, String>> buildUserList(String rootId) {
        return presenceStorage.getUserIdsInRoom(rootId)
                .stream()
                .map(id -> {
                    String nickname = presenceStorage.getNicknameByUserId(id);
                    Map<String, String> userMap = new java.util.HashMap<>();
                    userMap.put("uid", id);
                    userMap.put("userNickname", nickname != null ? nickname : "");
                    return userMap;
                })
                .toList();
    }

}
