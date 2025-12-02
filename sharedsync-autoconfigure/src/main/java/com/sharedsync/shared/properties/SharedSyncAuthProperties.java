package com.sharedsync.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sharedsync.auth")
public class SharedSyncAuthProperties {

    /**
     * true → 인증 + 인가 사용
     * false → 인증 완전 비활성화
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
