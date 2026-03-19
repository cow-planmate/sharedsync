package com.sharedsync.shared.repository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.history.HistoryAction;
import com.sharedsync.shared.id.IdPoolService;
import com.sharedsync.shared.storage.PresenceStorage;
import com.sharedsync.shared.repository.helper.CacheKeyHelper;
import com.sharedsync.shared.repository.helper.CriteriaQueryBuilder;
import com.sharedsync.shared.repository.helper.EntityConverterHelper;
import com.sharedsync.shared.repository.helper.RepositoryMetadata;
import com.sharedsync.shared.repository.helper.RepositoryReflectionUtils;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import lombok.extern.slf4j.Slf4j;

/**
 * 완전 자동화된 캐시 리포지토리.
 * DTO에 어노테이션만 추가하면 모든 CRUD 및 DB 동기화 기능이 자동으로 구현됩니다.
 *
 * <p>내부 구조:
 * <ul>
 *   <li>{@link RepositoryMetadata} – 리플렉션으로 추출한 메타데이터 (불변)</li>
 *   <li>{@link CacheDeletionManager} – 캐시 삭제 추적 및 cascade 삭제</li>
 *   <li>{@link DatabaseLoader} – DB 로딩·저장·동기화</li>
 * </ul>
 *
 * @param <T>   엔티티 타입
 * @param <ID>  ID 타입
 * @param <DTO> DTO 타입
 */
@Slf4j
public abstract class AutoCacheRepository<T, ID, DTO extends CacheDto<ID>> implements CacheRepository<T, ID, DTO> {

    @Autowired
    private ApplicationContext applicationContext;

    @PersistenceContext
    private EntityManager entityManager;

    // 생성자에서 초기화
    final RepositoryMetadata<T, ID, DTO> meta;

    // @PostConstruct에서 초기화
    private CriteriaQueryBuilder<T, ID> criteriaQueryBuilder;
    private CacheKeyHelper cacheKeyHelper;
    private EntityConverterHelper<T, ID, DTO> entityConverterHelper;
    private CacheDeletionManager<T, ID, DTO> deletionManager;
    private DatabaseLoader<T, ID, DTO> databaseLoader;

    @SuppressWarnings("unchecked")
    public AutoCacheRepository() {
        this.meta = new RepositoryMetadata<>(getClass());
    }

    public Class<DTO> getDtoClass() {
        return meta.dtoClass;
    }

    // =========================================================
    // 초기화
    // =========================================================

    @PostConstruct
    protected void initHelpers() {
        this.criteriaQueryBuilder = new CriteriaQueryBuilder<>(
                entityManager, meta.entityClass, meta.entityIdField, meta.parentEntityClassMap, meta.dtoFields);
        this.cacheKeyHelper = new CacheKeyHelper(meta.cacheKeyPrefix);
        this.entityConverterHelper = new EntityConverterHelper<>(
                meta.dtoClass, meta.entityClass, meta.entityConverterMethod,
                applicationContext, entityManager, meta.dtoFields);
        this.deletionManager = new CacheDeletionManager<>(
                meta, cacheKeyHelper, this::getCacheStore, applicationContext, this);
        this.databaseLoader = new DatabaseLoader<>(
                entityManager, meta, entityConverterHelper, criteriaQueryBuilder,
                cacheKeyHelper, applicationContext, this, deletionManager);
    }

    @PostConstruct
    private void initIdPool() {
        if (!meta.useIdPool) return;
        try {
            IdPoolService idPoolService = applicationContext.getBean(IdPoolService.class);
            idPoolService.registerPool(meta.sequenceName, meta.allocationSize);

            if (!idPoolService.isRedisPoolIntact()) {
                try {
                    String idFieldName = meta.entityIdField.getName();
                    Object result = entityManager.createQuery(
                            "SELECT MAX(e." + idFieldName + ") FROM " + meta.entityClass.getSimpleName() + " e")
                            .getSingleResult();
                    Long maxId = (result != null) ? ((Number) result).longValue() : null;
                    if (maxId != null) {
                        idPoolService.resetSequenceToMaxId(meta.sequenceName, maxId);
                        log.info("[AutoCacheRepository] Sequence '{}' reset to current max ID={} for entity={}",
                                meta.sequenceName, maxId, meta.entityClass.getSimpleName());
                    }
                } catch (Exception e) {
                    log.warn("[AutoCacheRepository] 시퀀스 리셋 실패 (무시하고 계속): {}", e.getMessage());
                }
            } else {
                log.info("[AutoCacheRepository] Redis pool exists for '{}', skipping sequence reset", meta.sequenceName);
            }

            idPoolService.initializePool(meta.sequenceName);
            log.info("[AutoCacheRepository] ID Pool initialized: entity={}, sequence={}, allocationSize={}",
                    meta.entityClass.getSimpleName(), meta.sequenceName, meta.allocationSize);
        } catch (Exception e) {
            log.warn("[AutoCacheRepository] ID Pool 초기화 실패 (fallback to negative ID): {}", e.getMessage());
        }
    }

    // =========================================================
    // CacheRepository 인터페이스 구현 – 기본 CRUD
    // =========================================================

    @Override
    public Optional<T> findById(ID id) {
        if (id != null) waitForLoading(id);
        DTO dto = getCacheStore().hashGet(getRedisKey(id), String.valueOf(id));
        if (dto == null) return Optional.empty();
        return Optional.of(convertToEntity(dto));
    }

    @Override
    public T getReferenceById(ID id) {
        return findById(id).orElseThrow(() -> new IllegalStateException("캐시에서 데이터를 찾을 수 없습니다: " + id));
    }

    @Override
    public void deleteById(ID id) {
        deletionManager.deleteCacheCascade(id);
    }

    @Override
    public boolean existsById(ID id) {
        return getCacheStore().hashGet(getRedisKey(id), String.valueOf(id)) != null;
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        if (ids == null) return Collections.emptyList();
        List<String> fields = new ArrayList<>();
        ids.forEach(id -> fields.add(String.valueOf(id)));
        if (fields.isEmpty()) return Collections.emptyList();

        List<DTO> dtos = getCacheStore().hashMutiGet(getRedisKey(null), fields);
        if (dtos == null) return Collections.emptyList();

        return dtos.stream()
                .filter(Objects::nonNull)
                .map(this::convertToEntity)
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DTO> saveAll(List<DTO> dtos) {
        for (ListIterator<DTO> iterator = dtos.listIterator(); iterator.hasNext();) {
            DTO dto = iterator.next();
            ID id = extractId(dto);
            id = changeType(id);

            if (id == null) {
                Object generatedId = null;
                if (meta.idClass.getSimpleName().equals("Integer")) {
                    generatedId = generateId().intValue();
                } else if (meta.idClass.getSimpleName().equals("Long")) {
                    generatedId = generateId();
                } else if (meta.idClass.getSimpleName().equals("String")) {
                    generatedId = String.valueOf(generateId());
                } else if (meta.idClass.getSimpleName().equals("UUID")) {
                    generatedId = java.util.UUID.randomUUID();
                }

                if (generatedId != null) {
                    dto = updateDtoWithId(dto, (ID) generatedId);
                    iterator.set(dto);
                    id = extractId(dto);
                }
            }

            String hashKey = getRedisKey(id);
            getCacheStore().hashSet(hashKey, String.valueOf(id), dto);
            deletionManager.removeFromDeletedSet(id);

            for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
                try {
                    Object parentId = entry.getKey().get(dto);
                    if (parentId != null) {
                        addIdToParentIndex(hashKey, entry.getValue(), parentId, id);
                    }
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        }
        return dtos;
    }

    @Override
    public void deleteAllById(Iterable<ID> ids) {
        if (ids == null) return;
        ids.forEach(id -> deletionManager.deleteCacheCascade(id));
    }

    // =========================================================
    // save / update
    // =========================================================

    @SuppressWarnings("unchecked")
    public DTO save(DTO dto) {
        ID id = extractId(dto);

        if (id == null) {
            Object generatedId = null;
            if (meta.idClass.getSimpleName().equals("UUID")) {
                generatedId = java.util.UUID.randomUUID();
            } else if (meta.idClass.getSimpleName().equals("Long")) {
                generatedId = generateId();
            } else if (meta.idClass.getSimpleName().equals("String")) {
                generatedId = String.valueOf(generateId());
            } else if (meta.idClass.getSimpleName().equals("Integer")) {
                generatedId = generateId().intValue();
            } else {
                generatedId = generateId().intValue();
            }
            dto = updateDtoWithId(dto, (ID) generatedId);
            id = extractId(dto);
        }

        String hashKey = getRedisKey(id);
        getCacheStore().hashSet(hashKey, String.valueOf(id), dto);
        deletionManager.removeFromDeletedSet(id);

        for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
            try {
                Object parentId = entry.getKey().get(dto);
                if (parentId != null) {
                    addIdToParentIndex(hashKey, entry.getValue(), parentId, id);
                }
            } catch (IllegalAccessException e) {
                // ignore
            }
        }
        return dto;
    }

    @SuppressWarnings("unchecked")
    public final void saveUnchecked(Object dto) {
        save((DTO) dto);
    }

    public DTO update(DTO dto) {
        ID id = extractId(dto);
        if (id == null) {
            throw new IllegalArgumentException("update는 ID가 필수입니다. save를 사용하세요.");
        }

        String hashKey = getRedisKey(id);
        deletionManager.removeFromDeletedSet(id);
        DTO existingDto = getCacheStore().hashGet(hashKey, String.valueOf(id));

        if (existingDto != null) {
            dto = mergeDto(existingDto, dto);
        }

        getCacheStore().hashSet(hashKey, String.valueOf(id), dto);

        for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
            Field field = entry.getKey();
            Class<?> parentClass = entry.getValue();
            try {
                Object oldId = (existingDto != null) ? field.get(existingDto) : null;
                Object newId = field.get(dto);
                if (oldId != null && !Objects.equals(oldId, newId)) {
                    removeIdFromParentIndex(hashKey, parentClass, oldId, id);
                }
                if (newId != null && !Objects.equals(newId, oldId)) {
                    addIdToParentIndex(hashKey, parentClass, newId, id);
                }
            } catch (IllegalAccessException e) {
                // ignore
            }
        }
        return dto;
    }

    // =========================================================
    // 삭제 – deletionManager 위임
    // =========================================================

    public void deleteCacheById(ID id) {
        if (id == null) return;
        deletionManager.deleteCacheCascade(id);
    }

    public void deleteCacheByParentId(ID parentId) {
        if (meta.parentIdFields.isEmpty() || parentId == null) return;
        deletionManager.removeEntriesByParentInternal(parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByParentIdUnchecked(Object parentId) {
        if (parentId == null) return;
        deleteCacheByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByIdUnchecked(Object id) {
        if (id == null) return;
        deleteCacheById((ID) id);
    }

    public void deleteCacheOnlyById(ID id) {
        if (id == null) return;
        deletionManager.deleteCacheOnlyCascade(id);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheOnlyByIdUnchecked(Object id) {
        if (id == null) return;
        deletionManager.deleteCacheOnlyCascade((ID) id);
    }

    public Set<String> getDeletedIds() {
        return deletionManager.getDeletedIds();
    }

    public void clearDeletedIds() {
        deletionManager.clearDeletedIds();
    }

    public void removeFromDeletedSet(ID id) {
        deletionManager.removeFromDeletedSet(id);
    }

    @SuppressWarnings("unchecked")
    public void removeFromDeletedSetUnchecked(Object id) {
        if (id == null) return;
        deletionManager.removeFromDeletedSet((ID) id);
    }

    public List<HistoryAction> collectCascadedHistory(ID id) {
        return deletionManager.collectCascadedHistory(id);
    }

    @SuppressWarnings("unchecked")
    public List<HistoryAction> collectCascadedHistoryUnchecked(Object id) {
        return deletionManager.collectCascadedHistoryUnchecked(id);
    }

    // =========================================================
    // 조회 – findBy*
    // =========================================================

    @Override
    public List<T> findByParentId(Object parentId) {
        return findByParentId(parentId, null);
    }

    public List<T> findByParentId(java.util.UUID parentId) {
        return findByParentId((Object) parentId, null);
    }

    public List<T> findByParentId(Integer parentId) {
        return findByParentId((Object) parentId, null);
    }

    @Override
    public List<T> findByParentId(Object parentId, Class<?> parentClass) {
        return findDtosByParentId(parentId, parentClass).stream()
                .map(this::convertToEntity)
                .toList();
    }

    @Override
    public List<T> deleteByParentId(Object parentId) {
        return deleteByParentId(parentId, null);
    }

    @Override
    public List<T> deleteByParentId(Object parentId, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }
        List<DTO> dtosToDelete = findDtosByParentId(parentId, parentClass);
        if (dtosToDelete.isEmpty()) return Collections.emptyList();

        dtosToDelete.stream()
                .map(this::extractId)
                .forEach(id -> deletionManager.deleteCacheCascade(id));

        return dtosToDelete.stream()
                .map(this::convertToEntity)
                .toList();
    }

    public List<DTO> findDtosByParentId(Object parentId) {
        return findDtosByParentId(parentId, null);
    }

    public List<DTO> findDtosByParentId(Object parentId, Class<?> parentClass) {
        if (meta.parentIdFields.isEmpty()) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }
        waitForLoading(parentId);

        String hashKey = getRedisKey(null);
        Set<String> allChildIds = new java.util.HashSet<>();

        if (parentClass != null) {
            String field = cacheKeyHelper.getParentIndexField(parentClass, parentId);
            String idListStr = getCacheStore().hashGetString(hashKey, field);
            if (idListStr != null && !idListStr.isEmpty()) {
                allChildIds.addAll(Arrays.asList(idListStr.split(",")));
            }
        } else {
            for (Class<?> pClass : meta.parentEntityClassMap.values()) {
                String field = cacheKeyHelper.getParentIndexField(pClass, parentId);
                String idListStr = getCacheStore().hashGetString(hashKey, field);
                if (idListStr != null && !idListStr.isEmpty()) {
                    allChildIds.addAll(Arrays.asList(idListStr.split(",")));
                }
            }
        }

        if (allChildIds.isEmpty()) return Collections.emptyList();

        List<DTO> allDtos = getCacheStore().hashMutiGet(hashKey, new ArrayList<>(allChildIds));
        if (allDtos == null) return Collections.emptyList();

        return allDtos.stream()
                .filter(Objects::nonNull)
                .filter(dto -> {
                    if (parentId == null) return false;
                    for (Map.Entry<Field, Class<?>> entry : meta.parentEntityClassMap.entrySet()) {
                        if (parentClass != null && !entry.getValue().equals(parentClass)) continue;
                        try {
                            Object dtoParentId = entry.getKey().get(dto);
                            if (dtoParentId == null) continue;
                            if (parentId.getClass().isInstance(dtoParentId)
                                    || dtoParentId.getClass().isInstance(parentId)) {
                                if (Objects.equals(parentId, dtoParentId)) return true;
                            }
                            if (parentId.toString().equals(dtoParentId.toString())) return true;
                        } catch (IllegalAccessException e) {
                            // ignore
                        }
                    }
                    return false;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<DTO> findDtosByParentIdUnchecked(Object parentId, Class<?> parentClass) {
        return findDtosByParentId((ID) parentId, parentClass);
    }

    public DTO findDtoById(ID id) {
        return getCacheStore().hashGet(getRedisKey(id), String.valueOf(id));
    }

    public List<DTO> findDtoListByParentId(ID parentId) {
        return findDtosByParentId(parentId);
    }

    @SuppressWarnings("unchecked")
    public DTO findDtoByIdUnchecked(Object id) {
        return findDtoById((ID) id);
    }

    @SuppressWarnings("unchecked")
    public List<DTO> findDtoListByParentIdUnchecked(Object parentId) {
        return findDtoListByParentId((ID) parentId);
    }

    public List<DTO> findByField(String fieldName, Object value) {
        if (fieldName == null || value == null) return Collections.emptyList();
        Field targetField = RepositoryReflectionUtils.findFieldInHierarchy(meta.dtoClass, fieldName);
        if (targetField == null) return Collections.emptyList();
        targetField.setAccessible(true);
        return findAllDtos().stream()
                .filter(dto -> matchesFieldValue(dto, targetField, value))
                .toList();
    }

    public List<T> findEntitiesByField(String fieldName, Object value) {
        return findByField(fieldName, value).stream()
                .map(this::convertToEntity)
                .toList();
    }

    public Optional<DTO> findOneByField(String fieldName, Object value) {
        List<DTO> results = findByField(fieldName, value);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<T> findOneEntityByField(String fieldName, Object value) {
        return findOneByField(fieldName, value).map(this::convertToEntity);
    }

    public List<DTO> findByFields(Map<String, Object> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) return Collections.emptyList();
        Map<Field, Object> fieldMap = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            Field field = RepositoryReflectionUtils.findFieldInHierarchy(meta.dtoClass, entry.getKey());
            if (field == null) return Collections.emptyList();
            field.setAccessible(true);
            fieldMap.put(field, entry.getValue());
        }
        return findAllDtos().stream()
                .filter(dto -> fieldMap.entrySet().stream()
                        .allMatch(e -> matchesFieldValue(dto, e.getKey(), e.getValue())))
                .toList();
    }

    public List<DTO> findAllDtos() {
        String hashKey = getRedisKey(null);
        Set<String> fields = getCacheStore().hashkeys(hashKey);
        if (fields == null || fields.isEmpty()) return Collections.emptyList();

        List<String> dataFields = fields.stream()
                .filter(f -> !f.startsWith("P_IDX:"))
                .toList();
        if (dataFields.isEmpty()) return Collections.emptyList();

        List<DTO> allDtos = getCacheStore().hashMutiGet(hashKey, dataFields);
        if (allDtos == null) return Collections.emptyList();

        return allDtos.stream().filter(Objects::nonNull).toList();
    }

    public List<T> findAllEntities() {
        return findAllDtos().stream().map(this::convertToEntity).toList();
    }

    // =========================================================
    // DB 로딩·동기화 – databaseLoader 위임
    // =========================================================

    @Override
    public final List<DTO> loadFromDatabaseByParentId(Object parentId) {
        return databaseLoader.loadFromDatabaseByParentId(parentId, null);
    }

    @Override
    public final List<DTO> loadFromDatabaseByParentId(Object parentId, Class<?> parentClass) {
        return databaseLoader.loadFromDatabaseByParentId(parentId, parentClass);
    }

    @SuppressWarnings("unchecked")
    public List<? extends CacheDto<?>> loadFromDatabaseByParentIdUnchecked(Object parentId) {
        return loadFromDatabaseByParentId(parentId);
    }

    public final DTO loadFromDatabaseById(ID id) {
        return databaseLoader.loadFromDatabaseById(id);
    }

    public DTO syncToDatabaseByDto(DTO dto) {
        return databaseLoader.syncToDatabaseByDto(dto);
    }

    @SuppressWarnings("unchecked")
    public DTO syncToDatabaseByDtoUnchecked(Object dto) {
        return databaseLoader.syncToDatabaseByDtoUnchecked(dto);
    }

    public List<DTO> syncToDatabaseByParentId(Object parentId) {
        return databaseLoader.syncToDatabaseByParentId(parentId, null);
    }

    public List<DTO> syncToDatabaseByParentId(Object parentId, Class<?> parentClass) {
        return databaseLoader.syncToDatabaseByParentId(parentId, parentClass);
    }

    @SuppressWarnings("unchecked")
    public List<DTO> syncToDatabaseByParentIdUnchecked(Object parentId) {
        return databaseLoader.syncToDatabaseByParentIdUnchecked(parentId);
    }

    public void deleteEntitiesNotInCache(Object parentId, Set<Object> persistentIds) {
        databaseLoader.deleteEntitiesNotInCache(parentId, persistentIds);
    }

    public void deleteEntitiesByDeletedSet() {
        databaseLoader.deleteEntitiesByDeletedSet();
    }

    public static void syncHierarchyToDatabaseByRootId(int rootId) { }
    public static void syncHierarchyToDatabaseByRootId(String rootId) { }

    // =========================================================
    // 공개 유틸 메서드
    // =========================================================

    public void mergeEntityFields(T target, T source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("target과 source는 null일 수 없습니다.");
        }
        try {
            for (Field field : target.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) continue;

                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)
                        || field.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                        || field.isAnnotationPresent(jakarta.persistence.OneToOne.class)
                        || field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)) {

                    Object sourceValue = field.get(source);
                    if (sourceValue != null) {
                        if (sourceValue instanceof Collection<?> sourceCollection) {
                            Object targetValue = field.get(target);
                            if (targetValue instanceof Collection targetCollection
                                    && targetCollection != sourceCollection) {
                                try {
                                    targetCollection.clear();
                                    ((Collection) targetCollection).addAll(sourceCollection);
                                } catch (Exception e) {
                                    field.set(target, sourceValue);
                                }
                                continue;
                            }
                        }
                        field.set(target, sourceValue);
                    }
                    continue;
                }

                Object sourceValue = field.get(source);
                if (sourceValue != null) {
                    field.set(target, sourceValue);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Entity 필드 병합 실패", e);
        }
    }

    public boolean isParentIdFieldPresent() {
        return !meta.parentIdFields.isEmpty();
    }

    public boolean isParentEntityOf(Class<?> potentialParentEntity) {
        for (Class<?> parentClass : meta.parentEntityClassMap.values()) {
            if (parentClass.isAssignableFrom(potentialParentEntity)) return true;
        }
        return false;
    }

    public Class<?> getEntityType() {
        return meta.entityClass;
    }

    public boolean isPersistentId(Object id) {
        return id != null && !isTemporaryId(id);
    }

    @SuppressWarnings("unchecked")
    public Object extractIdUnchecked(Object dto) {
        return extractId((DTO) dto);
    }

    public ID extractIdFromDtoUnchecked(Object dto) {
        return extractId((DTO) dto);
    }

    public boolean hasParentRepository(AutoCacheRepository<?, ?, ?> parentRepo) {
        if (parentRepo == null) return false;
        return meta.parentEntityClassMap.containsValue(parentRepo.meta.entityClass);
    }

    public List<String> getAvailableFieldNames() {
        return meta.dtoFields.stream().map(Field::getName).toList();
    }

    @SuppressWarnings("unchecked")
    public ID convertStringToId(String idStr) {
        if (idStr == null) return null;
        try {
            if (meta.idClass.equals(String.class)) return (ID) idStr;
            if (meta.idClass.equals(java.util.UUID.class)) return (ID) java.util.UUID.fromString(idStr);
            if (meta.idClass.equals(Long.class) || meta.idClass.equals(long.class)) return (ID) Long.valueOf(idStr);
            if (meta.idClass.equals(Integer.class) || meta.idClass.equals(int.class)) return (ID) Integer.valueOf(idStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("ID 변환 실패: " + idStr + " to " + meta.idClass.getSimpleName());
        }
        throw new IllegalArgumentException("지원하지 않는 ID 타입입니다: " + meta.idClass.getSimpleName());
    }

    // =========================================================
    // 로딩 상태 확인
    // =========================================================

    @Override
    public boolean isLoading(Object id) {
        if (id == null) return false;
        try {
            PresenceStorage presenceStorage = applicationContext.getBean(PresenceStorage.class);
            return presenceStorage.isLoading(id.toString());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isSyncing(Object id) {
        if (id == null) return false;
        try {
            PresenceStorage presenceStorage = applicationContext.getBean(PresenceStorage.class);
            return presenceStorage.isSyncing(id.toString());
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================
    // package-private 접근자 (CacheDeletionManager, DatabaseLoader에서 사용)
    // =========================================================

    CacheDeletionManager<T, ID, DTO> getDeletionManager() {
        return deletionManager;
    }

    DatabaseLoader<T, ID, DTO> getDatabaseLoader() {
        return databaseLoader;
    }

    protected final String getRedisKey(ID id) {
        return cacheKeyHelper.getRedisKey(id);
    }

    protected final String getDeletedSetKey() {
        return cacheKeyHelper.getDeletedSetKey();
    }

    @SuppressWarnings("unchecked")
    protected final CacheStore<DTO> getCacheStore() {
        String cacheStoreBeanName = meta.cacheKeyPrefix + "CacheStore";
        if (applicationContext.containsBean(cacheStoreBeanName)) {
            return (CacheStore<DTO>) applicationContext.getBean(cacheStoreBeanName);
        }
        if (applicationContext.containsBean("globalCacheStore")) {
            return (CacheStore<DTO>) applicationContext.getBean("globalCacheStore");
        }
        if (applicationContext.containsBean("fallbackCacheStore")) {
            return (CacheStore<DTO>) applicationContext.getBean("fallbackCacheStore");
        }
        try {
            return new RedisCacheStore<>(
                    (RedisTemplate<String, DTO>) applicationContext.getBean(meta.redisTemplateBeanName));
        } catch (Exception e) {
            return (CacheStore<DTO>) new InMemoryCacheStore<DTO>();
        }
    }

    @Deprecated
    @SuppressWarnings("unchecked")
    protected final RedisTemplate<String, DTO> getRedisTemplate() {
        return (RedisTemplate<String, DTO>) applicationContext.getBean(meta.redisTemplateBeanName);
    }

    protected final ID extractId(DTO dto) {
        try {
            return changeType((ID) meta.idField.get(dto));
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ID 필드에 접근할 수 없습니다: " + meta.idField.getName(), e);
        }
    }

    protected final List<Object> extractParentIds(DTO dto) {
        if (meta.parentIdFields.isEmpty()) return Collections.emptyList();
        List<Object> ids = new ArrayList<>();
        for (Field field : meta.parentIdFields) {
            try {
                Object val = field.get(dto);
                if (val != null) ids.add(val);
            } catch (IllegalAccessException e) {
                // ignore
            }
        }
        return ids;
    }

    protected T convertToEntity(DTO dto) {
        return entityConverterHelper.convertToEntity(dto);
    }

    protected DTO convertToDto(T entity) {
        return entityConverterHelper.convertToDto(entity);
    }

    // =========================================================
    // 내부 package-private 유틸 (헬퍼 클래스에서 접근)
    // =========================================================

    void addIdToParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        if (parentId == null || parentClass == null) return;
        String field = cacheKeyHelper.getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = getCacheStore().hashGetString(hashKey, field);
            if (existing == null || existing.isEmpty()) {
                getCacheStore().hashSetString(hashKey, field, idStr);
            } else {
                Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.add(idStr)) {
                    getCacheStore().hashSetString(hashKey, field, String.join(",", ids));
                }
            }
        }
    }

    void removeIdFromParentIndex(String hashKey, Class<?> parentClass, Object parentId, ID id) {
        if (parentId == null || parentClass == null) return;
        String field = cacheKeyHelper.getParentIndexField(parentClass, parentId);
        String idStr = String.valueOf(id);

        synchronized (this) {
            String existing = getCacheStore().hashGetString(hashKey, field);
            if (existing != null && !existing.isEmpty()) {
                Set<String> ids = new java.util.LinkedHashSet<>(Arrays.asList(existing.split(",")));
                if (ids.remove(idStr)) {
                    if (ids.isEmpty()) {
                        getCacheStore().hashDelete(hashKey, field);
                    } else {
                        getCacheStore().hashSetString(hashKey, field, String.join(",", ids));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    ID changeType(Object id) {
        if (id == null) return null;
        if (meta.idClass.isInstance(id)) return (ID) id;
        if (meta.idClass.getSimpleName().equals("String")) return (ID) id.toString();
        if (meta.idClass.getSimpleName().equals("Integer")) return (ID) Integer.valueOf(id.toString());
        if (meta.idClass.getSimpleName().equals("Long")) return (ID) Long.valueOf(id.toString());
        if (meta.idClass.getSimpleName().equals("UUID")) return (ID) java.util.UUID.fromString(id.toString());
        return null;
    }

    boolean isTemporaryId(Object id) {
        if (id == null) return false;
        if (meta.useIdPool) return false;
        if (id instanceof Number n) return n.longValue() < 0L;
        return false;
    }

    Object getParentIdValue(DTO dto) {
        if (meta.parentIdFields.isEmpty()) return null;
        try {
            for (Field field : meta.parentIdFields) {
                Object val = field.get(dto);
                if (val != null) return val;
            }
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ParentId 필드 접근 실패", e);
        }
    }

    void propagateParentIdChange(ID temporaryParentId, ID persistedParentId) {
        if (temporaryParentId == null || persistedParentId == null) return;
        Map<String, AutoCacheRepository<?, ?, ?>> repositories =
                (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>)
                        applicationContext.getBeansOfType(AutoCacheRepository.class);

        for (AutoCacheRepository<?, ?, ?> repo : repositories.values()) {
            if (repo == this) continue;
            if (repo.meta.parentEntityClassMap.isEmpty()) continue;
            boolean isParent = repo.meta.parentEntityClassMap.values().stream()
                    .anyMatch(pc -> pc.isAssignableFrom(meta.entityClass));
            if (isParent) {
                repo.updateParentReferenceInternal(temporaryParentId, persistedParentId);
            }
        }
    }

    // =========================================================
    // private 헬퍼
    // =========================================================

    private void waitForLoading(Object id) {
        if (id == null) return;
        try {
            PresenceStorage presenceStorage = applicationContext.getBean(PresenceStorage.class);
            for (int i = 0; i < 10; i++) {
                if (!presenceStorage.isLoading(id.toString())) return;
                if (i % 2 == 0) log.debug("[AutoCacheRepository] Waiting for loading... id={}", id);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private Long generateId() {
        if (meta.useIdPool) {
            try {
                IdPoolService idPoolService = applicationContext.getBean(IdPoolService.class);
                return idPoolService.nextId(meta.sequenceName);
            } catch (Exception e) {
                log.warn("[AutoCacheRepository] ID Pool에서 ID 할당 실패, 음수 ID fallback: {}", e.getMessage());
            }
        }
        String counterKey = "temporary:" + meta.cacheKeyPrefix + ":counter";
        return getCacheStore().decrement(counterKey);
    }

    private DTO updateDtoWithId(DTO dto, ID newId) {
        try {
            meta.idField.set(dto, newId);
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO ID 업데이트 실패: " + dto, e);
        }
    }

    private DTO mergeDto(DTO existingDto, DTO newDto) {
        try {
            for (Field field : meta.dtoFields) {
                if (field.equals(meta.idField)) continue;
                Object newValue = field.get(newDto);
                if (newValue != null) field.set(existingDto, newValue);
            }
            return existingDto;
        } catch (Exception e) {
            throw new RuntimeException("DTO 병합 실패: " + newDto, e);
        }
    }

    private boolean matchesFieldValue(DTO dto, Field field, Object expectedValue) {
        try {
            Object actualValue = field.get(dto);
            if (actualValue == null && expectedValue == null) return true;
            if (actualValue == null || expectedValue == null) return false;
            if (actualValue.getClass().equals(expectedValue.getClass())) return Objects.equals(actualValue, expectedValue);
            if (actualValue.getClass().isEnum() || expectedValue.getClass().isEnum()) return actualValue.toString().equals(expectedValue.toString());
            if (actualValue instanceof Number && expectedValue instanceof Number) return ((Number) actualValue).longValue() == ((Number) expectedValue).longValue();
            return actualValue.toString().equals(expectedValue.toString());
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void updateParentReferenceInternal(Object oldParentId, Object newParentId) {
        if (meta.parentIdFields.isEmpty() || oldParentId == null || newParentId == null) return;

        for (Field field : meta.parentIdFields) {
            if (!field.getType().isInstance(oldParentId) || !field.getType().isInstance(newParentId)) continue;

            String hashKey = getRedisKey(null);
            Class<?> parentClass = meta.parentEntityClassMap.get(field);
            if (parentClass == null) continue;

            String oldIndexField = cacheKeyHelper.getParentIndexField(parentClass, oldParentId);
            String idListStr = getCacheStore().hashGetString(hashKey, oldIndexField);
            if (idListStr == null || idListStr.isEmpty()) continue;

            List<String> fields = Arrays.asList(idListStr.split(","));
            List<DTO> dtos = getCacheStore().hashMutiGet(hashKey, fields);
            if (dtos == null || dtos.isEmpty()) continue;

            for (DTO dto : dtos) {
                if (dto == null) continue;
                try {
                    field.set(dto, newParentId);
                    ID dtoId = extractId(dto);
                    if (dtoId != null) {
                        getCacheStore().hashSet(hashKey, String.valueOf(dtoId), dto);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            getCacheStore().hashDelete(hashKey, oldIndexField);
            getCacheStore().hashSetString(hashKey, cacheKeyHelper.getParentIndexField(parentClass, newParentId), idListStr);
        }
    }
}
