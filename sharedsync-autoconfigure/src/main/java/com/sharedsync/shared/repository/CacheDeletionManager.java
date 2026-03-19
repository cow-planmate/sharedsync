package com.sharedsync.shared.repository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.repository.helper.CacheKeyHelper;
import com.sharedsync.shared.repository.helper.RepositoryMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * 캐시 삭제 추적 및 cascade 삭제를 담당합니다.
 * <p>
 * - DELETED Set 관리 (삭제된 영속 ID 추적)
 * - 캐시 cascade 삭제 (자식 엔티티 포함)
 * - 부모 삭제 전파 (다른 리포지토리로 삭제 연쇄)
 * - collectCascadedHistory (Undo/Redo용 히스토리 수집)
 */
@Slf4j
@SuppressWarnings("unchecked")
class CacheDeletionManager<T, ID, DTO extends CacheDto<ID>> {

    private final RepositoryMetadata<T, ID, DTO> meta;
    private final CacheKeyHelper cacheKeyHelper;
    private final Supplier<CacheStore<DTO>> cacheStoreSupplier;
    private final ApplicationContext applicationContext;
    private final AutoCacheRepository<T, ID, DTO> repository;

    CacheDeletionManager(
            RepositoryMetadata<T, ID, DTO> meta,
            CacheKeyHelper cacheKeyHelper,
            Supplier<CacheStore<DTO>> cacheStoreSupplier,
            ApplicationContext applicationContext,
            AutoCacheRepository<T, ID, DTO> repository) {
        this.meta = meta;
        this.cacheKeyHelper = cacheKeyHelper;
        this.cacheStoreSupplier = cacheStoreSupplier;
        this.applicationContext = applicationContext;
        this.repository = repository;
    }

    private CacheStore<DTO> store() {
        return cacheStoreSupplier.get();
    }

    // =========================================================
    // DELETED Set 관리
    // =========================================================

    void trackDeletedId(ID id) {
        if (id == null) return;
        if (!meta.useIdPool && id instanceof Number n && n.longValue() < 0L) return;
        store().addToSet(cacheKeyHelper.getDeletedSetKey(), String.valueOf(id));
    }

    Set<String> getDeletedIds() {
        return store().getSet(cacheKeyHelper.getDeletedSetKey());
    }

    void clearDeletedIds() {
        store().delete(cacheKeyHelper.getDeletedSetKey());
    }

    void removeFromDeletedSet(ID id) {
        if (id == null) return;
        store().removeFromSet(cacheKeyHelper.getDeletedSetKey(), String.valueOf(id));
    }

    void removeFromDeletedSetUnchecked(Object id) {
        if (id == null) return;
        removeFromDeletedSet((ID) id);
    }

    // =========================================================
    // 캐시 cascade 삭제
    // =========================================================

    void deleteCacheCascade(ID id) {
        if (id == null) return;
        trackDeletedId(id);

        String hashKey = cacheKeyHelper.getRedisKey(id);
        DTO dto = store().hashGet(hashKey, String.valueOf(id));
        if (dto != null) {
            for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        repository.removeIdFromParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }

        propagateParentDeletion(id);
        store().hashDelete(hashKey, String.valueOf(id));
    }

    void deleteCacheOnlyCascade(ID id) {
        if (id == null) return;

        String hashKey = cacheKeyHelper.getRedisKey(id);
        DTO dto = store().hashGet(hashKey, String.valueOf(id));
        if (dto != null) {
            for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        repository.removeIdFromParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }

        propagateCacheOnlyParentDeletion(id);
        store().hashDelete(hashKey, String.valueOf(id));
    }

    void deleteCacheOnlyByParentId(Object parentId, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty()) return;
        List<DTO> dtosToDelete = repository.findDtosByParentId(parentId, parentClass);
        if (dtosToDelete.isEmpty()) return;
        dtosToDelete.stream()
                .map(repository::extractId)
                .forEach(this::deleteCacheOnlyCascade);
    }

    // =========================================================
    // 부모 삭제 전파
    // =========================================================

    private void propagateParentDeletion(Object parentIdObject) {
        if (parentIdObject == null) return;
        Map<String, AutoCacheRepository<?, ?, ?>> repositories = allRepositories();
        Class<T> entityClass = meta.entityClass;

        for (AutoCacheRepository<?, ?, ?> repo : repositories.values()) {
            if (repo == this.repository) continue;
            if (repo.meta.parentEntityClassMap.isEmpty()) continue;
            for (Class<?> parentClass : repo.meta.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    repo.getDeletionManager().removeEntriesByParentInternal(parentIdObject, parentClass);
                }
            }
        }
    }

    private void propagateCacheOnlyParentDeletion(Object parentIdObject) {
        if (parentIdObject == null) return;
        Map<String, AutoCacheRepository<?, ?, ?>> repositories = allRepositories();
        Class<T> entityClass = meta.entityClass;

        for (AutoCacheRepository<?, ?, ?> repo : repositories.values()) {
            if (repo == this.repository) continue;
            if (repo.meta.parentEntityClassMap.isEmpty()) continue;
            for (Class<?> parentClass : repo.meta.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    repo.getDeletionManager().removeCacheOnlyEntriesByParentInternal(parentIdObject, parentClass);
                }
            }
        }
    }

    // =========================================================
    // 부모 ID로 자식 항목 제거 (내부용, 다른 매니저에서도 호출)
    // =========================================================

    void removeEntriesByParentInternal(Object parentIdObject) {
        if (parentIdObject == null) return;
        for (Class<?> parentClass : meta.parentEntityClassMap.values()) {
            removeEntriesByParentInternal(parentIdObject, parentClass);
        }
    }

    void removeEntriesByParentInternal(Object parentIdObject, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty() || parentIdObject == null) return;
        ID parentId = (ID) parentIdObject;
        List<DTO> dtos = repository.findDtosByParentId(parentId, parentClass);
        for (DTO dto : dtos) {
            ID childId = repository.extractId(dto);
            deleteCacheCascade(childId);
        }
    }

    void removeCacheOnlyEntriesByParentInternal(Object parentIdObject, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty() || parentIdObject == null) return;
        ID parentId = (ID) parentIdObject;
        List<DTO> childDtos = repository.findDtosByParentId(parentId, parentClass);
        childDtos.stream()
                .map(repository::extractId)
                .forEach(this::deleteCacheOnlyCascade);
    }

    // =========================================================
    // Undo/Redo 히스토리 수집
    // =========================================================

    List<HistoryAction> collectCascadedHistory(ID id) {
        if (id == null) return Collections.emptyList();

        List<HistoryAction> cascadedActions = new ArrayList<>();
        Map<String, AutoCacheRepository<?, ?, ?>> repositories = allRepositories();
        Class<T> entityClass = meta.entityClass;

        for (AutoCacheRepository<?, ?, ?> repo : repositories.values()) {
            if (repo == this.repository) continue;
            if (repo.meta.parentEntityClassMap.isEmpty()) continue;
            for (Class<?> parentClass : repo.meta.parentEntityClassMap.values()) {
                if (parentClass.isAssignableFrom(entityClass)) {
                    List<?> childDtos = repo.findDtosByParentIdUnchecked(id, parentClass);
                    if (!childDtos.isEmpty()) {
                        HistoryAction childAction = HistoryAction.builder()
                                .type(HistoryAction.Type.DELETE)
                                .entityName(repo.meta.cacheKeyPrefix)
                                .dtoClassName(repo.meta.dtoClass.getName())
                                .beforeData((List<? extends CacheDto<?>>) childDtos)
                                .afterData(null)
                                .subActions(new ArrayList<>())
                                .build();

                        for (Object childDto : childDtos) {
                            Object childId = repo.extractIdFromDtoUnchecked(childDto);
                            childAction.getSubActions().addAll(
                                    repo.getDeletionManager().collectCascadedHistoryUnchecked(childId));
                        }
                        cascadedActions.add(childAction);
                    }
                }
            }
        }
        return cascadedActions;
    }

    List<HistoryAction> collectCascadedHistoryUnchecked(Object id) {
        return collectCascadedHistory((ID) id);
    }

    // =========================================================
    // 내부 유틸
    // =========================================================

    private Map<String, AutoCacheRepository<?, ?, ?>> allRepositories() {
        return (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>)
                applicationContext.getBeansOfType(AutoCacheRepository.class);
    }
}
