package com.sharedsync.shared.presence.core;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PresenceBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(
            String entityName,
            String roomId,
            String action,
            String uid,
            String nickname,
            List<Map<String, String>> users
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", uid);
        payload.put("userNickname", nickname);
        payload.put("users", users); // 전체 리스트 추가

        messagingTemplate.convertAndSend(
                String.format("/topic/%s/%s/%s/presence", entityName, roomId, action),
                payload
        );
    }

}
