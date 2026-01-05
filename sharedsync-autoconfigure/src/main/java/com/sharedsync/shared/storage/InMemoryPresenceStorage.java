package com.sharedsync.shared.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 인메모리 기반 PresenceStorage 구현체.
 * 단일 인스턴스 환경에 적합합니다.
 */
@Component
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryPresenceStorage implements PresenceStorage {

    // rootId -> Map<userId//sessionId, index>
    private final Map<String, Map<String, String>> trackers = new ConcurrentHashMap<>();
    
    // sessionId -> rootId
    private final Map<String, String> sessionToRoot = new ConcurrentHashMap<>();
    
    // userId -> nickname
    private final Map<String, String> userNicknames = new ConcurrentHashMap<>();
    
    // nickname -> userId
    private final Map<String, String> nicknameToUser = new ConcurrentHashMap<>();

    // userId -> Set<sessionId>
    private final Map<String, java.util.Set<String>> userSessions = new ConcurrentHashMap<>();

    // rootId -> lock
    private final java.util.Set<String> syncLocks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean hasTracker(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        return tracker != null && !tracker.isEmpty();
    }

    @Override
    public void insertTracker(String rootId, String sessionId, String userId, String index) {
        trackers.computeIfAbsent(rootId, k -> new ConcurrentHashMap<>())
                .put(userId + "//" + sessionId, index);
    }

    @Override
    public void removeTracker(String rootId, String sessionId, String userId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker != null) {
            tracker.remove(userId + "//" + sessionId);
            if (tracker.isEmpty()) {
                trackers.remove(rootId);
            }
        }
    }

    @Override
    public Map<String, String> getTrackerEntries(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker == null) {
            return new ConcurrentHashMap<>();
        }
        return new ConcurrentHashMap<>(tracker);
    }

    @Override
    public void saveUserNickname(String userId, String nickname) {
        userNicknames.put(userId, nickname);
        nicknameToUser.put(nickname, userId);
    }

    @Override
    public String getNicknameByUserId(String userId) {
        return userNicknames.get(userId);
    }

    @Override
    public String getUserIdByNickname(String nickname) {
        return nicknameToUser.get(nickname);
    }

    @Override
    public void removeUserNickname(String userId) {
        String nickname = userNicknames.remove(userId);
        if (nickname != null) {
            nicknameToUser.remove(nickname);
        }
        userSessions.remove(userId);
    }

    @Override
    public boolean isUserActiveAnywhere(String userId) {
        java.util.Set<String> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    @Override
    public void addActiveSession(String userId, String sessionId) {
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
    }

    @Override
    public void removeActiveSession(String userId, String sessionId) {
        java.util.Set<String> sessions = userSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
            }
        }
    }

    @Override
    public void mapSessionToRoot(String sessionId, String rootId) {
        sessionToRoot.put(sessionId, rootId);
    }

    @Override
    public String getRootIdBySessionId(String sessionId) {
        return sessionToRoot.get(sessionId);
    }

    @Override
    public String removeSessionRootMapping(String sessionId) {
        return sessionToRoot.remove(sessionId);
    }

    @Override
    public List<String> getUserIdsInRoom(String rootId) {
        Map<String, String> tracker = trackers.get(rootId);
        if (tracker == null) {
            return new ArrayList<>();
        }
        List<String> userIds = new ArrayList<>();
        for (String key : tracker.keySet()) {
            String userId = key.split("//")[0]; // "userId//sessionId" → userId만 추출
            userIds.add(userId);
        }
        return userIds;
    }

    @Override
    public boolean acquireSyncLock(String rootId) {
        return syncLocks.add(rootId);
    }

    @Override
    public void releaseSyncLock(String rootId) {
        syncLocks.remove(rootId);
    }
}
