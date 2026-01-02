package com.sharedsync.shared.presence.storage;

import java.util.List;
import java.util.Map;

public interface PresenceStorage {
    boolean hasTracker(String rootId);
    void insertTracker(String rootId, String sessionId, String userId, String index);
    void removeTracker(String rootId, String sessionId, String userId);
    Map<String, String> getTrackerEntries(String rootId);

    void saveUserNickname(String userId, String nickname);
    String getNicknameByUserId(String userId);
    String getUserIdByNickname(String nickname);
    void removeUserNickname(String userId);
    boolean isUserActiveAnywhere(String userId);
    void addActiveSession(String userId, String sessionId);
    void removeActiveSession(String userId, String sessionId);

    void mapSessionToRoot(String sessionId, String rootId);
    String getRootIdBySessionId(String sessionId);
    String removeSessionRootMapping(String sessionId);
    List<String> getUserIdsInRoom(String rootId);

    boolean acquireSyncLock(String rootId);
    void releaseSyncLock(String rootId);
}
