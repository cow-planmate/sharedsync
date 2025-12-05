package com.sharedsync.shared.autoConfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.sharedsync.shared.auth.JwtHandshakeInterceptor;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

/**
 * SharedSync WebSocket 자동 설정 클래스.
 * Starter 의존성만 추가하면 WebSocket 환경이 항상 자동 구성된다.
 */
@AutoConfiguration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(SharedSyncWebSocketProperties.class)
public class SharedSyncWebSocketAutoConfig implements WebSocketMessageBrokerConfigurer {

    private final SharedSyncWebSocketProperties props;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    public SharedSyncWebSocketAutoConfig(SharedSyncWebSocketProperties props,
                                         JwtHandshakeInterceptor jwtHandshakeInterceptor) {
        this.props = props;
        this.jwtHandshakeInterceptor = jwtHandshakeInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(props.getEndpoint()) // ← YAML 반영됨
            .setAllowedOrigins(props.getAllowedOrigins().toArray(new String[0]))
            .addInterceptors(jwtHandshakeInterceptor)
            .withSockJS();
    }
}

