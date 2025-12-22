package com.sharedsync.shared.presence.storage;

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
    
    // userId -> rootId
    private final Map<String, String> userToRoot = new ConcurrentHashMap<>();
    
    // userId -> nickname
    private final Map<String, String> userNicknames = new ConcurrentHashMap<>();
    
    // nickname -> userId
    private final Map<String, String> nicknameToUser = new ConcurrentHashMap<>();

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
    public void mapUserToRoot(String rootId, String userId) {
        userToRoot.put(userId, rootId);
    }

    @Override
    public String getRootIdByUserId(String userId) {
        return userToRoot.get(userId);
    }

    @Override
    public String removeUserRootMapping(String userId) {
        return userToRoot.remove(userId);
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
}
