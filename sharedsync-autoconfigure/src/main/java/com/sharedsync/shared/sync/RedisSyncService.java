package com.sharedsync.shared.sync;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSyncService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final SharedSyncWebSocketProperties props;

    /**
     * 메시지를 Redis 채널로 발행합니다.
     * 모든 서버 인스턴스가 이 메시지를 수신하여 각자의 웹소켓 클라이언트에게 전달합니다.
     */
    public void publish(String destination, Object payload) {
        if (!props.getRedisSync().isEnabled()) {
            // Redis 동기화가 비활성화된 경우 로컬로 즉시 전송
            messagingTemplate.convertAndSend(destination, payload);
            return;
        }

        RedisSyncMessage message = RedisSyncMessage.builder()
                .destination(destination)
                .payload(payload)
                .build();

        redisTemplate.convertAndSend(props.getRedisSync().getChannel(), message);
    }

    /**
     * Redis로부터 수신한 메시지를 로컬 웹소켓 클라이언트들에게 전달합니다.
     */
    public void handleMessage(RedisSyncMessage message) {
        log.debug("Received sync message from Redis: destination={}, payload={}", 
                message.getDestination(), message.getPayload());
        
        messagingTemplate.convertAndSend(message.getDestination(), message.getPayload());
    }
}
