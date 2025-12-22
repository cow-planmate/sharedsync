package com.sharedsync.shared.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 인메모리 기반 CacheStore 구현체
 * Redis 없이 로컬 메모리에서 캐시를 관리합니다.
 * 개발/테스트 환경 또는 단일 인스턴스 배포 시 사용할 수 있습니다.
 *
 * @param <V> 값 타입 (DTO)
 */
public class InMemoryCacheStore<V> implements CacheStore<V> {

    private final Map<String, V> store = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public V get(String key) {
        return store.get(key);
    }

    @Override
    public void set(String key, V value) {
        store.put(key, value);
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public boolean hasKey(String key) {
        return store.containsKey(key);
    }

    @Override
    public List<V> multiGet(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<V> results = new ArrayList<>(keys.size());
        for (String key : keys) {
            results.add(store.get(key)); // null 포함 가능
        }
        return results;
    }

    @Override
    public Set<String> keys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptySet();
        }

        // Redis 패턴 "*"을 정규식으로 변환
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");

        Pattern compiled = Pattern.compile("^" + regex + "$");
        Set<String> result = new HashSet<>();

        for (String key : store.keySet()) {
            if (compiled.matcher(key).matches()) {
                result.add(key);
            }
        }
        return result;
    }

    @Override
    public Long decrement(String key) {
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong(0));
        return counter.decrementAndGet();
    }

    /**
     * 모든 캐시 데이터 삭제 (테스트용)
     */
    public void clear() {
        store.clear();
        counters.clear();
    }

    /**
     * 현재 캐시 크기 반환 (디버그용)
     */
    public int size() {
        return store.size();
    }

    /**
     * 모든 키 반환 (디버그용)
     */
    public Set<String> getAllKeys() {
        return Collections.unmodifiableSet(store.keySet());
    }
}
