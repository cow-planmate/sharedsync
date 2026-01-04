package com.sharedsync.shared.autoConfig;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.sharedsync.shared.config.RedisConfig;
import com.sharedsync.shared.config.RedisSyncConfig;
import com.sharedsync.shared.config.SharedWebSocketConfig;
import com.sharedsync.shared.properties.SharedSyncWebSocketProperties;

@Configuration
@EnableConfigurationProperties(SharedSyncWebSocketProperties.class)
@Import({RedisConfig.class, RedisSyncConfig.class, SharedWebSocketConfig.class})
@ComponentScan(basePackages = {"sharedsync", "com.sharedsync"})
public class SharedSyncAutoConfig {

}
