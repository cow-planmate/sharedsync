package com.sharedsync.shared.listener;

import com.sharedsync.shared.properties.SharedSyncAuthProperties;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.presence.core.PresenceBroadcaster;
import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.presence.core.UserProvider;
import com.sharedsync.shared.presence.storage.PresenceStorage;
import com.sharedsync.shared.sync.CacheSyncService;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

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
    public void handleConnect(String rootId, String userId, String sessionId) {
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
        presenceStorage.mapUserToRoot(rootId, userId);

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
        if (!authProperties.isEnabled()) {
            userId = "ws-" + sessionId;
        }

        String rootId = presenceStorage.removeUserRootMapping(userId);
        if (rootId == null || rootId.isBlank()) return;

        presenceStorage.removeTracker(rootId, sessionId, userId);

        if (!presenceStorage.hasTracker(rootId)) {
            cacheSyncService.syncToDatabase(rootId);
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
    }

    private List<Map<String, String>> buildUserList(String rootId) {
        return presenceStorage.getUserIdsInRoom(rootId)
                .stream()
                .map(id -> Map.of(
                        "uid", id,
                        "userNickname", presenceStorage.getNicknameByUserId(id)
                ))
                .toList();
    }

}
