package com.sharedsync.shared.presence.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.sharedsync.shared.sync.RedisSyncService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PresenceBroadcaster {

    private final RedisSyncService redisSyncService;

    public void broadcast(
            String entityName,
            String roomId,
            String action,
            String uid,
            Map<String, Object> userInfo,
            List<Map<String, Object>> users
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", uid);
        payload.put("userInfo", userInfo);
        payload.put("users", users); // 전체 리스트 추가
        payload.put("action", action); // 액션 추가

        redisSyncService.publish(
                String.format("/topic/%s/%s", entityName, roomId),
                payload
        );
    }

}
