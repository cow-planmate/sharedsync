package com.sharedsync.shared.listener;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import com.sharedsync.shared.presence.core.PresenceRootResolver;
import com.sharedsync.shared.properties.SharedSyncPresenceProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SharedEventTracker {

    private static final String USER_ID = "userId";
    private final PresenceSessionManager presenceSessionManager;
    private final PresenceRootResolver presenceRootResolver;
    private final SharedSyncPresenceProperties presenceProperties;

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        String channel = " ";
        if (presenceProperties.isEnabled()){
            channel = presenceRootResolver.getChannel();
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) return;

        
        if (!destination.startsWith("/topic/" + channel)) {
            String sessionId = accessor.getSessionId();
            String userId = extractUserId(accessor);
            String roomId = parseRoomId(destination);

            if (userId != null && roomId != null) {
                presenceSessionManager.handleSubscribe(roomId, userId, sessionId);
            }
        }
    }

    @EventListener
    public void handleDisconnectEvent(SessionDisconnectEvent event) {

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = extractUserId(accessor);
        String sessionId = accessor.getSessionId();
        presenceSessionManager.handleDisconnect(userId, sessionId);
        
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
        if (tokens.size() <= 2) {
            return null;
        }
        return tokens.get(2);
    }
}