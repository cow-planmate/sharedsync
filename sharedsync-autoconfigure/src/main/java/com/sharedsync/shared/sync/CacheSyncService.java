package com.sharedsync.shared.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sharedsync.shared.repository.AutoCacheRepository;
import com.sharedsync.shared.storage.PresenceStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheSyncService {
    private final List<AutoCacheRepository<?, ?, ?>> cacheRepositories;
    private final PresenceStorage presenceStorage;

    /**
     * 캐시 삭제 예약 항목 (Phase 2에서 일괄 삭제용)
     */
    private static class CacheDeletionEntry {
        final AutoCacheRepository<?, ?, ?> repository;
        final Object id;

        CacheDeletionEntry(AutoCacheRepository<?, ?, ?> repository, Object id) {
            this.repository = repository;
            this.id = id;
        }
    }

    @Transactional
    public void syncToDatabase(String rootId) {
        syncToDatabase(rootId, true);
    }

    /**
     * DB로 데이터를 동기화합니다.
     * 
     * @param clearCache true이면 동기화 성공 후 캐시에서 데이터를 삭제합니다.
     */
    @Transactional
    public void syncToDatabase(String rootId, boolean clearCache) {
        log.info("[CacheSync] [TRACE-F5] Sync request received for rootId={}, clearCache={}", rootId, clearCache);

        // 1. (사용자가 나갔을 때만) 동기화 도중 유저가 접속하면 중단 (이외 주기적 동기화는 무시)
        if (clearCache && presenceStorage.hasTracker(rootId)) {
            log.info("[CacheSync] [TRACE-F5] Sync aborted for rootId={}: Active tracker detected. (User returned)",
                    rootId);
            return;
        }

        // 2. 캐시 로딩(Initialize) 중이면 중단 (불완전 캐시 상태)
        // CacheInitializer에서 로딩 시작 시 설정하고 완료 시 해제함
        if (presenceStorage.isLoading(rootId)) {
            log.warn("[CacheSync] [TRACE-F5] Sync aborted for rootId={}: Cache initialization in progress.", rootId);
            return;
        }

        AutoCacheRepository<?, ?, ?> rootRepository = cacheRepositories.stream()
                .filter(repo -> !repo.isParentIdFieldPresent())
                .findFirst()
                .orElse(null);

        if (rootRepository == null) {
            log.warn("[CacheSync] No root repository found for sync");
            return;
        }

        Object rootIdTyped = rootRepository.convertStringToId(rootId);
        List<CacheDeletionEntry> deletionQueue = new ArrayList<>();

        syncRecursively(rootRepository, rootIdTyped, deletionQueue, rootId, clearCache);

        // Phase 2: 캐시 일괄 삭제 (트랜잭션 커밋 후 실행)
        if (clearCache && !deletionQueue.isEmpty()) {
            // DB 트랜잭션이 아직 활성 상태라면 afterCommit 동기화 등록
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info("[CacheSync] Transaction committed. Starting batch cache deletion for rootId={}",
                                rootId);
                        for (CacheDeletionEntry entry : deletionQueue) {
                            try {
                                entry.repository.deleteCacheByIdUnchecked(entry.id);
                            } catch (Exception e) {
                                log.error("[CacheSync] Failed to delete cache for id={} in repo={}", entry.id,
                                        entry.repository.getClass().getSimpleName(), e);
                            }
                        }
                    }
                });
            } else {
                // 트랜잭션이 없는 경우 (거의 없겠지만) 즉시 삭제
                log.warn("[CacheSync] No active transaction. Deleting cache immediately for rootId={}", rootId);
                for (CacheDeletionEntry entry : deletionQueue) {
                    entry.repository.deleteCacheByIdUnchecked(entry.id);
                }
            }
        }
    }

    private void syncRecursively(AutoCacheRepository<?, ?, ?> repository, Object id,
            List<CacheDeletionEntry> deletionQueue, String rootId, boolean clearCache) {
        if (repository == null || id == null) {
            return;
        }

        // 1. (사용자가 나갔을 때만) 동기화 도중 유저가 접속하면 중지 (이외 주기적 동기화는 무시)
        if (clearCache && presenceStorage.hasTracker(rootId)) {
            log.info("[CacheSync] Aborting recursive sync for rootId={} because user activity detected", rootId);
            return;
        }

        Object dto = repository.findDtoByIdUnchecked(id);
        if (dto != null) {
            repository.syncToDatabaseByDtoUnchecked(dto);
        }

        // Phase 1: 자식 엔티티 동기화 (Leaf부터 상향식으로 진행됨)
        Map<AutoCacheRepository<?, ?, ?>, List<?>> childDtos = cacheRepositories.stream()
                .filter(childRepo -> childRepo.hasParentRepository(repository))
                .collect(Collectors.toMap(childRepo -> childRepo,
                        childRepo -> childRepo.findDtoListByParentIdUnchecked(id)));

        for (Map.Entry<AutoCacheRepository<?, ?, ?>, List<?>> entry : childDtos.entrySet()) {
            AutoCacheRepository<?, ?, ?> childRepo = entry.getKey();

            List<?> dtos = entry.getValue();
            if (dtos == null) {
                dtos = List.of();
            }

            dtos.stream()
                    .filter(Objects::nonNull)
                    .forEach(childRepo::syncToDatabaseByDtoUnchecked);

            List<?> refreshed = childRepo.findDtoListByParentIdUnchecked(id);
            if (refreshed == null) {
                refreshed = List.of();
            }
            Set<Object> persistentIds = refreshed.stream()
                    .map(childRepo::extractIdUnchecked)
                    .filter(Objects::nonNull)
                    .filter(childRepo::isPersistentId)
                    .collect(Collectors.toSet());

                Set<Object> tombstonedIds = childRepo.consumeDeletedPersistentIdsByParentUnchecked(
                    id,
                    repository.getEntityType());
                childRepo.deleteEntitiesByIdsUnchecked(tombstonedIds);

            persistentIds.forEach(childId -> syncRecursively(childRepo, childId, deletionQueue, rootId, clearCache));
        }
        // 캐시 삭제를 위해 큐에 추가
        if (clearCache) {
            deletionQueue.add(new CacheDeletionEntry(repository, id));
        }
    }
}
