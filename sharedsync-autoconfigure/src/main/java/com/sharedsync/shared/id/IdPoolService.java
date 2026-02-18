package com.sharedsync.shared.id;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * DB 시퀀스에서 ID를 배치로 미리 할당하고, 엔티티 타입별 Pool로 관리하는 서비스.
 * <p>
 * save() 시점에 DB 접근 없이 즉시 유효한 양수 ID를 반환합니다.
 * Pool이 소진되면 동기적으로 DB에서 추가 할당합니다.
 */
@Service
@Slf4j
public class IdPoolService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 시퀀스 이름 → IdPool 맵핑
     */
    private final Map<String, IdPool> pools = new ConcurrentHashMap<>();

    /**
     * 시퀀스 이름에 대한 Pool을 등록합니다.
     * AutoCacheRepository 초기화 시 호출됩니다.
     */
    public void registerPool(String sequenceName, int allocationSize) {
        pools.computeIfAbsent(sequenceName, key -> {
            IdPool pool = new IdPool(key, allocationSize);
            log.info("[IdPoolService] Pool registered: sequenceName={}, allocationSize={}", key, allocationSize);
            return pool;
        });
    }

    /**
     * 지정된 시퀀스에서 다음 ID를 반환합니다.
     * Pool이 비어있으면 동기적으로 DB에서 할당합니다.
     *
     * @param sequenceName DB 시퀀스 이름
     * @return 다음 유효한 양수 ID
     */
    public Long nextId(String sequenceName) {
        IdPool pool = pools.get(sequenceName);
        if (pool == null) {
            throw new IllegalStateException("[IdPoolService] 등록되지 않은 시퀀스: " + sequenceName);
        }

        Long id = pool.poll();
        if (id != null) {
            // 비동기 리필 트리거 (임계값 이하일 때)
            if (pool.needsRefill() && pool.tryStartRefill()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        refillPool(pool);
                    } finally {
                        pool.finishRefill();
                    }
                });
            }
            return id;
        }

        // Pool이 비어있음 → 동기적으로 리필 후 재시도
        log.warn("[IdPoolService] Pool exhausted for '{}'. Refilling synchronously...", sequenceName);
        refillPoolSync(pool);
        id = pool.poll();
        if (id == null) {
            throw new IllegalStateException("[IdPoolService] Pool 리필 후에도 ID를 할당할 수 없습니다: " + sequenceName);
        }
        return id;
    }

    /**
     * Pool에 대한 초기 ID 할당을 수행합니다.
     * 애플리케이션 시작 시 또는 최초 사용 시 호출됩니다.
     */
    public void initializePool(String sequenceName) {
        IdPool pool = pools.get(sequenceName);
        if (pool == null) {
            log.warn("[IdPoolService] 초기화 시 등록되지 않은 시퀀스: {}", sequenceName);
            return;
        }
        if (!pool.isEmpty()) {
            return; // 이미 초기화됨
        }
        refillPoolSync(pool);
    }

    /**
     * DB 시퀀스에서 ID를 배치로 가져와 Pool에 추가합니다 (비동기용).
     */
    private void refillPool(IdPool pool) {
        try {
            List<Long> ids = fetchIdsFromSequence(pool.getSequenceName(), pool.getAllocationSize());
            pool.addAll(ids);
            log.info("[IdPoolService] Pool refilled asynchronously: sequenceName={}, count={}, poolSize={}",
                    pool.getSequenceName(), ids.size(), pool.size());
        } catch (Exception e) {
            log.error("[IdPoolService] Async pool refill failed for '{}': {}", pool.getSequenceName(), e.getMessage());
        }
    }

    /**
     * DB 시퀀스에서 ID를 배치로 가져와 Pool에 추가합니다 (동기).
     */
    private void refillPoolSync(IdPool pool) {
        List<Long> ids = fetchIdsFromSequence(pool.getSequenceName(), pool.getAllocationSize());
        pool.addAll(ids);
        log.info("[IdPoolService] Pool refilled synchronously: sequenceName={}, count={}, poolSize={}",
                pool.getSequenceName(), ids.size(), pool.size());
    }

    /**
     * PostgreSQL 시퀀스에서 N개의 ID를 가져옵니다.
     * setval로 시퀀스를 N만큼 전진시키고, 그 범위의 ID를 반환합니다.
     */
    private List<Long> fetchIdsFromSequence(String sequenceName, int count) {
        if (entityManager == null) {
            throw new IllegalStateException("[IdPoolService] EntityManager is not available");
        }

        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object result = entityManager
                    .createNativeQuery("SELECT nextval(:seqName)")
                    .setParameter("seqName", sequenceName)
                    .getSingleResult();
            ids.add(((Number) result).longValue());
        }

        log.debug("[IdPoolService] Fetched {} IDs from sequence '{}': range [{} ~ {}]",
                count, sequenceName,
                ids.isEmpty() ? "N/A" : ids.get(0),
                ids.isEmpty() ? "N/A" : ids.get(ids.size() - 1));

        return ids;
    }

    /**
     * 등록된 모든 Pool의 상태를 반환합니다 (디버깅/모니터링용).
     */
    public Map<String, Integer> getPoolStatus() {
        Map<String, Integer> status = new ConcurrentHashMap<>();
        pools.forEach((name, pool) -> status.put(name, pool.size()));
        return status;
    }
}
