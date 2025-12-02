package com.sharedsync.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sharedsync.presence")
public class SharedSyncPresenceProperties {

    /**
     * presence 기능 사용 여부
     */
    private boolean enabled = true; // 기본값 true

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
