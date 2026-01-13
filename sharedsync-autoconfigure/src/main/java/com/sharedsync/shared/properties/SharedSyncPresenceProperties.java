package com.sharedsync.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sharedsync.presence")
public class SharedSyncPresenceProperties {

    /**
     * presence 기능 사용 여부
     */
    private boolean enabled = true; // 기본값 true

    /**
     * 세션 만료 시간 (단위: 초). 기본값 1시간.
     * 이 시간 동안 활동이 없으면 좀비 데이터로 간주되어 삭제될 수 있습니다.
     */
    private long sessionTimeout = 60*60; // 기본값 1시간

    /**
     * 백그라운드 좀비 데이터 정리 주기 (단위: 초).
     * 0으로 설정하면 백그라운드 정리를 사용하지 않습니다.
     */
    private long cleanupInterval = 30;

    /**
     * 구독 후 최초 상태 브로드캐스트 지연 시간 (단위: 밀리초).
     * 클라이언트의 구독 처리가 완전히 완료된 후 메시지를 받도록 지연시킵니다.
     */
    private long broadcastDelay = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(long sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public long getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public long getBroadcastDelay() {
        return broadcastDelay;
    }

    public void setBroadcastDelay(long broadcastDelay) {
        this.broadcastDelay = broadcastDelay;
    }
}
