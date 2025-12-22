package com.sharedsync.shared.repository;

import java.util.List;
import java.util.Set;

/**
 * 캐시 저장소 추상화 인터페이스
 * Redis 또는 InMemory 구현체를 교체할 수 있습니다.
 *
 * @param <V> 값 타입 (DTO)
 */
public interface CacheStore<V> {

    /**
     * 키로 값 조회
     */
    V get(String key);

    /**
     * 키-값 저장
     */
    void set(String key, V value);

    /**
     * 키 삭제
     */
    void delete(String key);

    /**
     * 키 존재 여부 확인
     */
    boolean hasKey(String key);

    /**
     * 여러 키로 값 조회
     */
    List<V> multiGet(List<String> keys);

    /**
     * 패턴에 맞는 모든 키 조회 (예: "plan:*")
     */
    Set<String> keys(String pattern);

    /**
     * 카운터 감소 (원자적) - 임시 ID 생성용
     */
    Long decrement(String key);
}
