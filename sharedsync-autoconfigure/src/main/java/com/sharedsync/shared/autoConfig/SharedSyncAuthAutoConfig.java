package com.sharedsync.shared.autoConfig;

import com.sharedsync.shared.config.SharedSyncAuthProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(SharedSyncAuthProperties.class)
public class SharedSyncAuthAutoConfig {
    // 빈 등록이 필요 없고, properties만 열어주면 됨
}
