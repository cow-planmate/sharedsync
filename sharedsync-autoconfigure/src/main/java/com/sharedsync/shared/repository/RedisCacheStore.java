package com.sharedsync.shared.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 기반 CacheStore 구현체
 * 기존 RedisTemplate을 래핑하여 CacheStore 인터페이스를 구현합니다.
 *
 * @param <V> 값 타입 (DTO)
 */
public class RedisCacheStore<V> implements CacheStore<V> {

    private final RedisTemplate<String, V> redisTemplate;

    public RedisCacheStore(RedisTemplate<String, V> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public V get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, V value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public List<V> multiGet(List<String> keys) {
        return redisTemplate.opsForValue().multiGet(keys);
    }

    @Override
    public Set<String> keys(String pattern) {
        return redisTemplate.keys(pattern);
    }

    @Override
    public Long decrement(String key) {
        return redisTemplate.opsForValue().decrement(key);
    }

    /**
     * 내부 RedisTemplate 접근 (하위 호환용)
     */
    public RedisTemplate<String, V> getRedisTemplate() {
        return redisTemplate;
    }
}
