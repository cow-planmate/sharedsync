package com.sharedsync.shared.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "redis")
public class RedisPresenceStorage implements PresenceStorage {

    private final RedisTemplate<String, Object> redis;

    private static final String TRACKER = "PRESENCE:TRACKER:";
    private static final String SESSION_TO_ROOT = "PRESENCE:SESSION_ROOT:";
    private static final String NICKNAME = "PRESENCE:NICKNAME:";
    private static final String NAME_TO_ID = "PRESENCE:NAME_ID:";
    private static final String USER_SESSIONS = "PRESENCE:USER_SESSIONS:";
    private static final String SYNC_LOCK = "PRESENCE:SYNC_LOCK:";

    @Override
    public boolean hasTracker(String rootId) {
        return redis.hasKey(TRACKER + rootId);
    }

    @Override
    public void insertTracker(String rootId, String sessionId, String userId, String index) {
        redis.opsForHash().put(TRACKER + rootId, String.valueOf(userId + "//" + sessionId), index);
    }

    @Override
    public void removeTracker(String rootId, String sessionId, String userId) {
        redis.opsForHash().delete(TRACKER + rootId, String.valueOf(userId + "//" + sessionId));
    }

    @Override
    public Map<String, String> getTrackerEntries(String rootId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TRACKER + rootId);
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                String key = entry.getKey().toString();
                String value = entry.getValue().toString();
                result.put(key, value);
            } catch (NumberFormatException e) {
                // skip invalid entries
            }
        }
        return result;
    }

    @Override
    public void saveUserNickname(String userId, String nickname) {
        redis.opsForValue().set(NICKNAME + userId, nickname);
        redis.opsForValue().set(NAME_TO_ID + nickname, userId);
    }

    @Override
    public String getNicknameByUserId(String userId) {
        return (String) redis.opsForValue().get(NICKNAME + userId);
    }

    @Override
    public String getUserIdByNickname(String nickname) {
        Object v = redis.opsForValue().get(NAME_TO_ID + nickname);
        return v == null ? null : (String) v;
    }

    @Override
    public void removeUserNickname(String userId) {
        String nickname = getNicknameByUserId(userId);
        if (nickname != null) {
            redis.delete(NAME_TO_ID + nickname);
        }
        redis.delete(NICKNAME + userId);
        redis.delete(USER_SESSIONS + userId);
    }

    @Override
    public boolean isUserActiveAnywhere(String userId) {
        Long size = redis.opsForSet().size(USER_SESSIONS + userId);
        return size != null && size > 0;
    }

    @Override
    public void addActiveSession(String userId, String sessionId) {
        redis.opsForSet().add(USER_SESSIONS + userId, sessionId);
    }

    @Override
    public void removeActiveSession(String userId, String sessionId) {
        redis.opsForSet().remove(USER_SESSIONS + userId, sessionId);
    }

    @Override
    public void mapSessionToRoot(String sessionId, String rootId) {
        redis.opsForValue().set(SESSION_TO_ROOT + sessionId, rootId);
    }

    @Override
    public String getRootIdBySessionId(String sessionId) {
        Object v = redis.opsForValue().get(SESSION_TO_ROOT + sessionId);
        return v == null ? null : (String) v;
    }

    @Override
    public String removeSessionRootMapping(String sessionId) {
        Object v = redis.opsForValue().getAndDelete(SESSION_TO_ROOT + sessionId);
        return v == null ? null : (String) v;
    }

    @Override
    public List<String> getUserIdsInRoom(String rootId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TRACKER + rootId);

        List<String> list = new ArrayList<>();
        for (Object k : entries.keySet()) {
            String key = k.toString();
            String userId = key.split("//")[0]; // "userId//sessionId" → userId만 추출
            list.add(userId);
        }
        return list;
    }

    @Override
    public boolean acquireSyncLock(String rootId) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(SYNC_LOCK + rootId, "locked", java.time.Duration.ofSeconds(30)));
    }

    @Override
    public void releaseSyncLock(String rootId) {
        redis.delete(SYNC_LOCK + rootId);
    }

}

