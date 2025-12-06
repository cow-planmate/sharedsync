package com.sharedsync.shared.listener;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SharedEventTracker {

    private static final String USER_ID = "userId";
    private static final String SIMP_SESSION_ID = "simpSessionId";
    private final PresenceSessionManager presenceSessionManager;

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = extractSessionId(accessor);
        String userId = extractUserId(accessor);
        String roomId = parseRoomId(accessor.getDestination());

        if (userId != null && roomId != null) {
            presenceSessionManager.handleSubscribe(roomId, userId, sessionId);
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = extractUserId(accessor);
        String sessionId = extractSessionId(accessor);
        if (userId != null) {
            presenceSessionManager.handleDisconnect(userId, sessionId);
        }
    }

    /**
     * SockJS 사용 시 Subscribe와 Disconnect에서 sessionId가 다를 수 있음.
     * sessionAttributes에 저장된 simpSessionId를 우선 사용하여 일관성 보장.
     */
    private String extractSessionId(StompHeaderAccessor accessor) {
        if (accessor.getSessionAttributes() != null) {
            Object storedSessionId = accessor.getSessionAttributes().get(SIMP_SESSION_ID);
            if (storedSessionId != null) {
                return String.valueOf(storedSessionId);
            }
        }
        return accessor.getSessionId();
    }

    private String extractUserId(StompHeaderAccessor accessor) {
        Object value = accessor.getSessionAttributes().get(USER_ID);
        if (value == null) {
            return null;
        }
        try {
            return String.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String parseRoomId(String destination) {
        if (destination == null) return null;
        List<String> tokens = List.of(destination.split("/"));
        return tokens.get(2);
    }
}