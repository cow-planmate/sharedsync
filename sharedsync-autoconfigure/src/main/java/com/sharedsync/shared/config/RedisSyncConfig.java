package com.sharedsync.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;
import com.sharedsync.shared.sync.RedisSyncMessage;
import com.sharedsync.shared.sync.RedisSyncService;

import lombok.RequiredArgsConstructor;

@Configuration
@ConditionalOnProperty(name = "sharedsync.websocket.redis-sync.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisSyncConfig {

    private final SharedSyncWebSocketProperties props;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(props.getRedisSync().getChannel()));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(RedisSyncService redisSyncService, ObjectMapper objectMapper) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(new Object() {
            @SuppressWarnings("unused")
            public void handleMessage(Object message) {
                try {
                    RedisSyncMessage syncMessage;
                    if (message instanceof RedisSyncMessage) {
                        syncMessage = (RedisSyncMessage) message;
                    } else if (message instanceof byte[]) {
                        syncMessage = objectMapper.readValue((byte[]) message, RedisSyncMessage.class);
                    } else {
                        syncMessage = objectMapper.convertValue(message, RedisSyncMessage.class);
                    }
                    redisSyncService.handleMessage(syncMessage);
                } catch (Exception e) {
                    // 로깅 등 예외 처리
                }
            }
        }, "handleMessage");
        
        Jackson2JsonRedisSerializer<RedisSyncMessage> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, RedisSyncMessage.class);
        adapter.setSerializer(serializer);
        return adapter;
    }
}
