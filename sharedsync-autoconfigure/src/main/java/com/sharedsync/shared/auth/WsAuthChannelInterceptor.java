package com.sharedsync.shared.auth;

import java.security.Principal;
import java.util.List;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.sharedsync.shared.properties.SharedSyncAuthProperties;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WsAuthChannelInterceptor implements ChannelInterceptor {

    private final AuthenticationTokenResolver tokenResolver;
    private final List<StompAccessValidator> accessValidators;
    private final SharedSyncAuthProperties authProperties; // ← 추가됨

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        // 🚀 데모 모드: 모든 STOMP 인증/권한 검사 비활성화
        if (!authProperties.isEnabled()) {
            return message;
        }

        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null) return message;

        switch (acc.getCommand()) {
            case CONNECT -> handleConnect(acc);
            case SUBSCRIBE -> handleSubscribe(acc);
            case SEND -> handleSend(acc);
        }

        return message;
    }

    private void handleConnect(StompHeaderAccessor acc) {
        String auth = firstNative(acc, "Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new AccessDeniedException("Missing Authorization");
        }

        String token = auth.substring(7);
        if (!tokenResolver.validate(token)) {
            throw new AccessDeniedException("Invalid token");
        }

        int userId = Integer.parseInt(tokenResolver.extractPrincipalId(token));
        acc.setUser(new StompPrincipal(userId));
        
        // SockJS sessionId 불일치 문제 해결: simpSessionId를 sessionAttributes에 저장
        String simpSessionId = acc.getSessionId();
        if (simpSessionId != null && acc.getSessionAttributes() != null) {
            acc.getSessionAttributes().put("simpSessionId", simpSessionId);
        }
    }

    private void handleSubscribe(StompHeaderAccessor acc) {
        handleValidate(acc);
    }

    private void handleSend(StompHeaderAccessor acc) {
        handleValidate(acc);
    }

    private void handleValidate(StompHeaderAccessor acc) {
        String dest = acc.getDestination();
        if (dest == null) return;

        Principal p = acc.getUser();
        if (p == null) throw new AccessDeniedException("Unauthenticated");

        Integer userId = ((StompPrincipal) p).userId();

        for (StompAccessValidator validator : accessValidators) {
            if (validator.supports(dest)) {
                validator.validate(userId, dest);
                return;
            }
        }
    }

    private String firstNative(StompHeaderAccessor acc, String key) {
        var values = acc.getNativeHeader(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private record StompPrincipal(Integer userId) implements Principal {
        @Override public String getName() { return "u:" + userId; }
    }
}




