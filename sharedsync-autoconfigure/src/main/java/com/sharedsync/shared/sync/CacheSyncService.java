package com.sharedsync.shared.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sharedsync.shared.repository.AutoCacheRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CacheSyncService {
    private final List<AutoCacheRepository<?, ?, ?>> cacheRepositories;

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
        AutoCacheRepository<?, ?, ?> rootRepository = cacheRepositories.stream()
                .filter(repo -> !repo.isParentIdFieldPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("루트 DTO를 가진 AutoCacheRepository를 찾을 수 없습니다."));

        // Phase 1: DB 동기화 수행 (캐시는 그대로 유지, 삭제 대상만 수집)
        List<CacheDeletionEntry> deletionQueue = new ArrayList<>();
        syncRecursively(rootRepository, rootId, deletionQueue);

        // Phase 2: 캐시 일괄 삭제
        // DB 동기화가 완전히 끝난 후에 캐시를 삭제하므로,
        // 조회 시 "캐시 전부 있음" 또는 "캐시 전부 없음(DB fallback)" 상태만 노출됩니다.
        for (CacheDeletionEntry entry : deletionQueue) {
            entry.repository.deleteCacheByIdUnchecked(entry.id);
        }
    }

    private void syncRecursively(AutoCacheRepository<?, ?, ?> repository, Object id,
            List<CacheDeletionEntry> deletionQueue) {
        if (repository == null || id == null) {
            return;
        }

        Object dto = repository.findDtoByIdUnchecked(id);
        if (dto != null) {
            repository.syncToDatabaseByDtoUnchecked(dto);
        }

        Map<AutoCacheRepository<?, ?, ?>, List<?>> childDtos = cacheRepositories.stream()
                .filter(childRepo -> childRepo != repository)
                .filter(childRepo -> childRepo.isParentEntityOf(repository.getEntityType()))
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

            childRepo.deleteEntitiesNotInCache(id, persistentIds);

            persistentIds.forEach(childId -> syncRecursively(childRepo, childId, deletionQueue));
        }
        // 캐시 삭제를 바로 하지 않고, 삭제 대상 큐에 추가 (Phase 2에서 일괄 삭제)
        deletionQueue.add(new CacheDeletionEntry(repository, id));
    }
}
