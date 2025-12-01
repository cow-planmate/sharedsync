package com.sharedsync.shared.autoConfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.sharedsync.shared.presence.core.SharedPresenceFacade;
import com.sharedsync.shared.presence.storage.PresenceStorage;

@AutoConfiguration
public class SharedPresenceAutoConfig {

    @Bean
    public SharedPresenceFacade sharedPresenceFacade(PresenceStorage presenceStorage) {
        return new SharedPresenceFacade(presenceStorage);
    }
}
