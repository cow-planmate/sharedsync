package com.sharedsync.shared.presence.core;

/**
 * Presence 기능이 비활성화된 경우에 사용하는 더미 UserProvider 구현체.
 * 실제 닉네임 조회 대신 항상 "anonymous"를 리턴.
 */
public class DummyUserProvider implements UserProvider {

    @Override
    public String findNicknameByUserId(String userId) {
        return "anonymous";
    }
}
