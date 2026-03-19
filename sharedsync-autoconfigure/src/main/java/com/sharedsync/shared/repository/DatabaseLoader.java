package com.sharedsync.shared.repository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.helper.CacheKeyHelper;
import com.sharedsync.shared.repository.helper.CriteriaQueryBuilder;
import com.sharedsync.shared.repository.helper.EntityConverterHelper;
import com.sharedsync.shared.repository.helper.RepositoryMetadata;
import com.sharedsync.shared.repository.helper.RepositoryReflectionUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * DB 로딩, 저장, 동기화를 담당합니다.
 * <p>
 * - DB에서 엔티티 로딩 (parentId 기반, ID 기반)
 * - 캐시 → DB 동기화 (syncToDatabase*)
 * - 삭제 추적 Set 기반 DB 삭제 (deleteEntitiesByDeletedSet)
 * - Pool ID를 사용한 native INSERT (insertWithOverridingSystemValue)
 */
@Slf4j
@SuppressWarnings({"unchecked", "null"})
class DatabaseLoader<T, ID, DTO extends CacheDto<ID>> {

    private final EntityManager entityManager;
    private final RepositoryMetadata<T, ID, DTO> meta;
    private final EntityConverterHelper<T, ID, DTO> entityConverterHelper;
    private final CriteriaQueryBuilder<T, ID> criteriaQueryBuilder;
    private final CacheKeyHelper cacheKeyHelper;
    private final ApplicationContext applicationContext;
    private final AutoCacheRepository<T, ID, DTO> repository;
    private final CacheDeletionManager<T, ID, DTO> deletionManager;

    DatabaseLoader(
            EntityManager entityManager,
            RepositoryMetadata<T, ID, DTO> meta,
            EntityConverterHelper<T, ID, DTO> entityConverterHelper,
            CriteriaQueryBuilder<T, ID> criteriaQueryBuilder,
            CacheKeyHelper cacheKeyHelper,
            ApplicationContext applicationContext,
            AutoCacheRepository<T, ID, DTO> repository,
            CacheDeletionManager<T, ID, DTO> deletionManager) {
        this.entityManager = entityManager;
        this.meta = meta;
        this.entityConverterHelper = entityConverterHelper;
        this.criteriaQueryBuilder = criteriaQueryBuilder;
        this.cacheKeyHelper = cacheKeyHelper;
        this.applicationContext = applicationContext;
        this.repository = repository;
        this.deletionManager = deletionManager;
    }

    // =========================================================
    // DB 로딩
    // =========================================================

    List<T> loadEntitiesByParentId(Object parentId, Class<?> parentClass) {
        if (entityManager == null) return Collections.emptyList();

        if (!meta.parentIdFields.isEmpty()) {
            try {
                return criteriaQueryBuilder.loadEntitiesByCriteria(parentId, parentClass);
            } catch (Exception e) {
                log.error("[SharedSync] Criteria API findByParent 실패: {}", e.getMessage());
            }
        } else {
            try {
                return criteriaQueryBuilder.loadAllEntitiesByCriteria();
            } catch (Exception e) {
                log.error("[SharedSync] Criteria API findAll 실패: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    List<DTO> loadFromDatabaseByParentId(Object parentId, Class<?> parentClass) {
        List<DTO> dtos = loadEntitiesByParentId(parentId, parentClass).stream()
                .map(entityConverterHelper::convertToDto)
                .toList();

        try {
            deletionManager.deleteCacheOnlyByParentId(parentId, parentClass);
        } catch (Exception e) {
            // ignore
        }

        if (!dtos.isEmpty()) {
            repository.saveAll(dtos);
        }
        return dtos;
    }

    DTO loadFromDatabaseById(ID id) {
        id = repository.changeType(id);
        try {
            T entity = criteriaQueryBuilder.loadEntityByIdCriteria(id);
            if (entity == null) return null;
            DTO dto = entityConverterHelper.convertToDto(entity);
            repository.save(dto);
            return dto;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    // DB 동기화
    // =========================================================

    DTO syncToDatabaseByDto(DTO dto) {
        if (dto == null) return null;
        if (!meta.parentIdFields.isEmpty()) {
            Object parentIdValue = repository.getParentIdValue(dto);
            if (parentIdValue instanceof Number n && n.longValue() < 0) return null;
        }
        return saveToDatabase(dto);
    }

    DTO syncToDatabaseByDtoUnchecked(Object dto) {
        return syncToDatabaseByDto((DTO) dto);
    }

    List<DTO> syncToDatabaseByParentId(Object parentId, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }
        if (parentId == null) return Collections.emptyList();
        if (parentId instanceof Number n && n.longValue() < 0L) return Collections.emptyList();

        List<DTO> cachedDtos = repository.findDtosByParentId(parentId, parentClass);
        if (!cachedDtos.isEmpty()) {
            cachedDtos.forEach(this::syncToDatabaseByDto);
        }

        List<DTO> refreshedDtos = repository.findDtosByParentId(parentId, parentClass);
        Set<ID> cachedPersistentIds = refreshedDtos.stream()
                .map(repository::extractId)
                .filter(Objects::nonNull)
                .filter(id -> !repository.isTemporaryId(id))
                .collect(Collectors.toSet());

        List<T> persistedEntities = loadEntitiesByParentId(parentId, parentClass);
        if (persistedEntities == null || persistedEntities.isEmpty()) return Collections.emptyList();

        if (cachedPersistentIds.isEmpty()) {
            log.error("[CacheRepository] [TRACE-F5] CRITICAL: Sync found EMPTY CACHE for parentId={} but DB has {} entities. Aborting delete to prevent wipeout.",
                    parentId, persistedEntities.size());
            return refreshedDtos;
        }

        log.info("[CacheRepository] [TRACE-F5] ParentId={}: Cache has {} persistent items, DB has {} items. Proceeding with sync.",
                parentId, cachedPersistentIds.size(), persistedEntities.size());

        List<T> entitiesToDelete = persistedEntities.stream()
                .filter(entity -> {
                    ID entityId = extractEntityId(entity);
                    return entityId != null && !cachedPersistentIds.contains(entityId);
                })
                .collect(Collectors.toList());

        if (!entitiesToDelete.isEmpty()) {
            handleChildCleanupBeforeDelete(entitiesToDelete);
            deleteAllEntities(entitiesToDelete);
        }
        return refreshedDtos;
    }

    List<DTO> syncToDatabaseByParentIdUnchecked(Object parentId) {
        return syncToDatabaseByParentId((ID) parentId, null);
    }

    void syncToDatabaseByParentIdInternal(Object parentIdObject, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty() || parentIdObject == null) return;
        syncToDatabaseByParentId((ID) parentIdObject, parentClass);
    }

    void deleteEntitiesNotInCache(Object parentId, Set<Object> persistentIds) {
        if (meta.parentIdFields.isEmpty() || parentId == null) return;

        boolean typeMatched = meta.parentIdFields.stream()
                .anyMatch(field -> field.getType().isInstance(parentId));
        if (!typeMatched) return;

        if (persistentIds == null || persistentIds.isEmpty()) {
            log.error("[CacheRepository] CRITICAL: Attempt to delete ALL entities for parentId={} from DB. Aborting deletion to prevent data loss.", parentId);
            return;
        }

        List<T> persistedEntities = loadEntitiesByParentId((ID) parentId, null);
        if (persistedEntities == null || persistedEntities.isEmpty()) return;

        Set<ID> allowedIds = persistentIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> meta.entityIdField.getType().isInstance(id))
                .map(id -> (ID) id)
                .collect(Collectors.toSet());

        List<T> targets = persistedEntities.stream()
                .filter(entity -> {
                    ID entityId = extractEntityId(entity);
                    return entityId != null && !allowedIds.contains(entityId);
                })
                .collect(Collectors.toList());

        if (targets.isEmpty()) return;
        handleChildCleanupBeforeDelete(targets);
        deleteAllEntities(targets);
    }

    void deleteEntitiesByDeletedSet() {
        Set<String> deletedIdStrings = deletionManager.getDeletedIds();
        if (deletedIdStrings == null || deletedIdStrings.isEmpty()) return;

        log.info("[CacheRepository] Processing deleted set: entity={}, count={}, ids={}",
                meta.cacheKeyPrefix, deletedIdStrings.size(), deletedIdStrings);

        List<T> entitiesToDelete = new ArrayList<>();
        for (String idStr : deletedIdStrings) {
            try {
                ID typedId = repository.convertStringToId(idStr);
                if (typedId == null) continue;
                T entity = (T) entityManager.find(meta.entityClass, typedId);
                if (entity != null) entitiesToDelete.add(entity);
            } catch (Exception e) {
                log.warn("[CacheRepository] Failed to find entity for deleted id={}: {}", idStr, e.getMessage());
            }
        }

        if (!entitiesToDelete.isEmpty()) {
            handleChildCleanupBeforeDelete(entitiesToDelete);
            deleteAllEntities(entitiesToDelete);
            log.info("[CacheRepository] Deleted {} entities from DB by tracked set for entity={}",
                    entitiesToDelete.size(), meta.cacheKeyPrefix);
        }
        deletionManager.clearDeletedIds();
    }

    // =========================================================
    // private 헬퍼
    // =========================================================

    private void handleChildCleanupBeforeDelete(List<T> entitiesToDelete) {
        if (entitiesToDelete == null || entitiesToDelete.isEmpty()) return;

        Map<String, AutoCacheRepository<?, ?, ?>> repositories =
                (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>)
                        applicationContext.getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = meta.entityClass;

        for (T entity : entitiesToDelete) {
            ID parentId = extractEntityId(entity);
            if (parentId == null) continue;

            for (AutoCacheRepository<?, ?, ?> repo : repositories.values()) {
                if (repo.meta.parentEntityClassMap.isEmpty()) continue;
                for (Class<?> parentClass : repo.meta.parentEntityClassMap.values()) {
                    if (parentClass.isAssignableFrom(entityClass)) {
                        repo.getDatabaseLoader().syncToDatabaseByParentIdInternal(parentId, parentClass);
                        repo.getDeletionManager().removeEntriesByParentInternal(parentId, parentClass);
                    }
                }
            }
        }
    }

    private DTO saveToDatabase(DTO dto) {
        T entity = entityConverterHelper.convertToEntity(dto);

        ID previousId = extractEntityId(entity);
        boolean hasPersistentId = previousId != null && !repository.isTemporaryId(previousId);

        boolean isPooledNewEntity = false;
        if (meta.useIdPool && hasPersistentId) {
            T existing = entityManager.find(meta.entityClass, previousId);
            if (existing == null) isPooledNewEntity = true;
        }

        if (!hasPersistentId) {
            setEntityId(entity, null);
        }

        T entityToSave = entity;
        if (hasPersistentId && !isPooledNewEntity) {
            ID persistedId = Objects.requireNonNull(previousId);
            T origin = entityManager.find(meta.entityClass, persistedId);
            if (origin != null) {
                repository.mergeEntityFields(origin, entity);
                entityToSave = origin;
            }
        }

        // 필수 ManyToOne 관계 null 체크
        try {
            for (Field f : meta.entityClass.getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.ManyToOne.class)) {
                    jakarta.persistence.JoinColumn jc = f.getAnnotation(jakarta.persistence.JoinColumn.class);
                    if (jc != null && !jc.nullable()) {
                        f.setAccessible(true);
                        Object val = f.get(entityToSave);
                        if (val == null) {
                            log.warn("[SharedSync][WARN] Required ManyToOne relation is null - skipping DB save: {}.{}",
                                    meta.entityClass.getSimpleName(), f.getName());
                            return dto;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SharedSync][WARN] failed to validate required relations: {}", e.getMessage());
        }

        T savedEntity = isPooledNewEntity
                ? insertWithOverridingSystemValue(entityToSave)
                : saveEntity(entityToSave);

        DTO updatedDto = entityConverterHelper.convertToDto(savedEntity);
        ID cacheId = repository.extractId(updatedDto);

        if (cacheId != null) {
            DTO dtoToCache = Objects.requireNonNull(updatedDto);
            repository.getCacheStore().hashSet(cacheKeyHelper.getRedisKey(cacheId), String.valueOf(cacheId), dtoToCache);
        }

        if (repository.isTemporaryId(previousId) && !repository.isTemporaryId(cacheId)) {
            repository.propagateParentIdChange(previousId, cacheId);
        }

        if (previousId != null && !Objects.equals(previousId, cacheId)) {
            String staleKey = cacheKeyHelper.getRedisKey(previousId);
            repository.getCacheStore().hashDelete(staleKey, String.valueOf(previousId));

            for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(updatedDto);
                    if (parentId != null) {
                        repository.removeIdFromParentIndex(staleKey, entry.getValue(), parentId, previousId);
                        repository.addIdToParentIndex(cacheKeyHelper.getRedisKey(cacheId), entry.getValue(), parentId, cacheId);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }
        return updatedDto;
    }

    private T insertWithOverridingSystemValue(T entity) {
        String tableName = RepositoryReflectionUtils.getTableName(meta.entityClass);
        List<String> columnNames = new ArrayList<>();
        List<Object> columnValues = new ArrayList<>();

        for (Field field : getAllPersistableFields(meta.entityClass)) {
            field.setAccessible(true);

            if (field.isAnnotationPresent(jakarta.persistence.Transient.class)
                    || Modifier.isStatic(field.getModifiers())
                    || Modifier.isFinal(field.getModifiers())) continue;

            if (field.isAnnotationPresent(jakarta.persistence.OneToMany.class)) continue;

            try {
                Object value = field.get(entity);

                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)) {
                    jakarta.persistence.JoinColumn joinColumn = field.getAnnotation(jakarta.persistence.JoinColumn.class);
                    if (joinColumn == null) continue;

                    boolean isNullable = joinColumn.nullable();
                    if (value == null) {
                        if (!isNullable) {
                            log.warn("[AutoCacheRepository] Required FK is null for {}.{}, skipping INSERT",
                                    meta.entityClass.getSimpleName(), field.getName());
                            return entity;
                        }
                        continue;
                    }

                    Object fkValue = extractIdFromRelatedEntity(value);
                    if (fkValue == null) {
                        if (!isNullable) {
                            log.warn("[AutoCacheRepository] FK value is null for {}.{} (entity exists but ID is null), skipping INSERT",
                                    meta.entityClass.getSimpleName(), field.getName());
                            return entity;
                        }
                        continue;
                    }
                    columnNames.add(joinColumn.name());
                    columnValues.add(fkValue);
                    continue;
                }

                String columnName = RepositoryReflectionUtils.getColumnName(field);
                if (columnName != null && value != null) {
                    columnNames.add(columnName);
                    columnValues.add(value);
                }
            } catch (IllegalAccessException e) {
                log.warn("[AutoCacheRepository] Failed to access field {}: {}", field.getName(), e.getMessage());
            }
        }

        if (columnNames.isEmpty()) {
            log.error("[AutoCacheRepository] No columns to insert for entity: {}", meta.entityClass.getSimpleName());
            return entity;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (");
        sql.append(String.join(", ", columnNames));
        sql.append(") OVERRIDING SYSTEM VALUE VALUES (");
        sql.append(columnNames.stream().map(c -> "?").collect(Collectors.joining(", ")));
        sql.append(")");

        try {
            jakarta.persistence.Query query = entityManager.createNativeQuery(sql.toString());
            for (int i = 0; i < columnValues.size(); i++) {
                query.setParameter(i + 1, columnValues.get(i));
            }
            query.executeUpdate();
            log.info("[AutoCacheRepository] Inserted entity with Pool ID: table={}, id={}",
                    tableName, extractEntityId(entity));
        } catch (Exception e) {
            log.error("[AutoCacheRepository] Native INSERT failed for {}: {}", meta.entityClass.getSimpleName(), e.getMessage());
            log.warn("[AutoCacheRepository] Entity will be retried on next periodic sync. id={}", extractEntityId(entity));
        }
        return entity;
    }

    private T saveEntity(T entity) {
        ID id = extractEntityId(entity);
        if (id == null) {
            entityManager.persist(entity);
            return entity;
        }
        try {
            if (!meta.ignoredEntityFields.isEmpty()) {
                T existing = entityManager.find(meta.entityClass, id);
                if (existing != null) {
                    for (Field f : meta.ignoredEntityFields) {
                        try {
                            f.set(entity, f.get(existing));
                        } catch (IllegalAccessException e) {
                            // ignore
                        }
                    }
                }
            }
            return entityManager.merge(entity);
        } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
            return entity;
        }
    }

    private void deleteAllEntities(List<T> entities) {
        for (T entity : entities) {
            try {
                T managed = entityManager.contains(entity) ? entity : entityManager.merge(entity);
                entityManager.remove(managed);
            } catch (jakarta.persistence.OptimisticLockException | org.hibernate.StaleObjectStateException e) {
                // already deleted
            }
        }
    }

    private List<Field> getAllPersistableFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private Object extractIdFromRelatedEntity(Object relatedEntity) {
        if (relatedEntity == null) return null;
        Class<?> clazz = relatedEntity.getClass();
        try {
            if (relatedEntity instanceof org.hibernate.proxy.HibernateProxy proxy) {
                Object identifier = proxy.getHibernateLazyInitializer().getIdentifier();
                if (identifier != null) return identifier;
                clazz = proxy.getHibernateLazyInitializer().getPersistentClass();
            }
        } catch (Exception e) {
            // not a proxy
        }
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)
                        || field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class)) {
                    field.setAccessible(true);
                    try {
                        return field.get(relatedEntity);
                    } catch (IllegalAccessException e) {
                        try {
                            String getter = "get" + Character.toUpperCase(field.getName().charAt(0))
                                    + field.getName().substring(1);
                            return clazz.getMethod(getter).invoke(relatedEntity);
                        } catch (Exception ex) {
                            log.warn("[AutoCacheRepository] Cannot access @Id field '{}' in {}: {}",
                                    field.getName(), clazz.getSimpleName(), e.getMessage());
                        }
                    }
                }
            }
            current = current.getSuperclass();
        }
        log.warn("[AutoCacheRepository] No @Id field found in {}", clazz.getSimpleName());
        return null;
    }

    private ID extractEntityId(T entity) {
        if (entity == null) return null;
        try {
            return (ID) meta.entityIdField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 접근 실패", e);
        }
    }

    private void setEntityId(T entity, Object value) {
        if (entity == null) return;
        try {
            meta.entityIdField.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 설정 실패", e);
        }
    }
}
