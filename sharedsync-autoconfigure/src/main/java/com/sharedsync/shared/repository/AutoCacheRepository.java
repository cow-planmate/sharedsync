package com.sharedsync.shared.repository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

import com.sharedsync.shared.annotation.AutoDatabaseLoader;
import com.sharedsync.shared.annotation.AutoEntityConverter;
import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 완전 자동화된 캐시 리포지토리
 * DTO에 어노테이션만 추가하면 모든 CRUD 및 DB 동기화 기능이 자동으로 구현됩니다.
 *
 * @param <T> 엔티티 타입
 * @param <ID> ID 타입  
 * @param <DTO> DTO 타입
 */
public abstract class AutoCacheRepository<T, ID, DTO extends CacheDto<ID>> implements CacheRepository<T, ID, DTO> {

    @Autowired
    private ApplicationContext applicationContext;
    
    @PersistenceContext
    private EntityManager entityManager;

    private final Class<DTO> dtoClass;
    private final String cacheKeyPrefix;
    private final Field idField;
    private final Field parentIdField;
    private final Class<?> parentEntityClass;
    private final Method entityConverterMethod;
    private final Field entityIdField;
    private final Class<ID> idClass;
    private final String parentEntityFieldName; // Entity에서 parent를 참조하는 필드명
    private final String redisTemplateBeanName;
    private final String repositoryBeanName;
    private final String loadMethodName;
    private final String[] entityConverterRepositories;
    private final List<Field> dtoFields;

    @SuppressWarnings("unchecked")
    public AutoCacheRepository() {
        // 제네릭 타입에서 DTO 클래스 추출
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
            this.dtoClass = (Class<DTO>) typeArgs[2];
        } else {
            throw new IllegalStateException("DTO 클래스를 추출할 수 없습니다.");
        }

        // @CacheEntity 어노테이션에서 키 타입 추출
        Cache cacheAnnotation = dtoClass.getAnnotation(Cache.class);
        if (cacheAnnotation == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheEntity 어노테이션이 없습니다.");
        }

        // keyType이 빈 문자열이면 클래스 이름에서 자동 생성
        String annotationKeyType = cacheAnnotation.keyType();
        if (annotationKeyType == null || annotationKeyType.isEmpty()) {
            // PlanDto -> "plan"
            this.cacheKeyPrefix = dtoClass.getSimpleName().replace("Dto", "").toLowerCase();
        } else {
            this.cacheKeyPrefix = annotationKeyType.toLowerCase();
        }

        // @AutoRedisTemplate 어노테이션에서 Redis 템플릿 이름 추출
        String entityName = dtoClass.getSimpleName().replace("Dto", "");
        this.redisTemplateBeanName = Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1) + "Redis";


        // @AutoDatabaseLoader 어노테이션에서 Repository와 메서드 정보 추출
        AutoDatabaseLoader dbLoaderAnnotation = dtoClass.getAnnotation(AutoDatabaseLoader.class);
        if (dbLoaderAnnotation != null) {
            this.repositoryBeanName = dbLoaderAnnotation.repository().isEmpty() ?
                    generateRepositoryName() : dbLoaderAnnotation.repository();
            this.loadMethodName = dbLoaderAnnotation.method().isEmpty() ?
                    generateLoadMethodName() : dbLoaderAnnotation.method();
        } else {
            this.repositoryBeanName = generateRepositoryName();
            this.loadMethodName = generateLoadMethodName();
        }

        // @AutoEntityConverter 어노테이션에서 필요한 Repository들 추출
        AutoEntityConverter entityConverterAnnotation = dtoClass.getAnnotation(AutoEntityConverter.class);
        if (entityConverterAnnotation != null) {
            this.entityConverterRepositories = entityConverterAnnotation.repositories();
        } else {
            this.entityConverterRepositories = new String[0];
        }

        // 필드와 메서드 찾기
        this.idField = findFieldWithAnnotation(dtoClass, CacheId.class);
        if (this.idField == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheId 어노테이션이 붙은 필드가 없습니다.");
        }
        this.idField.setAccessible(true);

        this.parentIdField = findFieldWithAnnotation(dtoClass, ParentId.class);
        if (this.parentIdField != null) {
            this.parentIdField.setAccessible(true);
            ParentId parentIdAnnotation = this.parentIdField.getAnnotation(ParentId.class);
            if (parentIdAnnotation != null && parentIdAnnotation.value() != Object.class) {
                this.parentEntityClass = parentIdAnnotation.value();
            } else {
                this.parentEntityClass = null;
            }
        } else {
            this.parentEntityClass = null;
        }

        this.entityConverterMethod = findMethodWithAnnotation(dtoClass, EntityConverter.class);
        if (this.entityConverterMethod == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @EntityConverter 어노테이션이 붙은 메서드가 없습니다.");
        }
        this.entityConverterMethod.setAccessible(true);

        Field detectedEntityIdField = locateEntityIdField(getEntityClass());
        if (detectedEntityIdField == null) {
            throw new IllegalStateException("@Id 필드를 찾을 수 없습니다: " + getEntityClass().getSimpleName());
        }
        detectedEntityIdField.setAccessible(true);
        this.entityIdField = detectedEntityIdField;
        @SuppressWarnings("unchecked")
        Class<ID> detectedIdClass = (Class<ID>) detectedEntityIdField.getType();
        this.idClass = detectedIdClass;

        this.dtoFields = Arrays.stream(dtoClass.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        
        // Entity에서 parent를 참조하는 필드명 찾기 (Criteria API용)
        this.parentEntityFieldName = findParentEntityFieldName();
    }
    
    /**
     * Entity 클래스에서 parentEntityClass를 참조하는 @ManyToOne 필드명을 찾습니다.
     * 예: Memo 엔티티의 userShelfBook 필드 → "userShelfBook"
     */
    private String findParentEntityFieldName() {
        if (parentEntityClass == null) {
            return null;
        }
        
        Class<T> entityClass = getEntityClass();
        for (Field field : entityClass.getDeclaredFields()) {
            // @ManyToOne 관계이고, 타입이 parentEntityClass와 일치하는지 확인
            if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class)) {
                if (field.getType().equals(parentEntityClass)) {
                    return field.getName();
                }
            }
        }
        
        // @ManyToOne이 없으면 타입으로만 매칭 시도
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.getType().equals(parentEntityClass)) {
                return field.getName();
            }
        }
        
        // DTO의 parentIdField 이름에서 추론 (예: userShelfBookId → userShelfBook)
        if (parentIdField != null) {
            String fieldName = parentIdField.getName();
            if (fieldName.endsWith("Id")) {
                return fieldName.substring(0, fieldName.length() - 2);
            }
        }
        
        return null;
    }

    // ==== CacheRepository 인터페이스 기본 CRUD 구현 ====

    @Override
    public Optional<T> findById(ID id) {
        DTO dto = getRedisTemplate().opsForValue().get(getRedisKey(id));
        if (dto == null) {
            return Optional.empty();
        }
        return Optional.of(convertToEntity(dto));
    }

    @Override
    public T getReferenceById(ID id) {
        return findById(id).orElseThrow(() ->
                new IllegalStateException("캐시에서 데이터를 찾을 수 없습니다: " + id));
    }

    @Override
    public void deleteById(ID id) {
        deleteCacheCascade(id);
    }

    @Override
    public boolean existsById(ID id) {
        return Boolean.TRUE.equals(getRedisTemplate().hasKey(getRedisKey(id)));
    }

    @Override
    public List<T> findAllById(Iterable<ID> ids) {
        List<String> keys = new ArrayList<>();
        ids.forEach(id -> keys.add(getRedisKey(id)));

        List<DTO> dtos = getRedisTemplate().opsForValue().multiGet(keys);
        if (dtos == null) {
            return Collections.emptyList();
        }

        return dtos.stream()
                .filter(dto -> dto != null)
                .map(this::convertToEntity)
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DTO> saveAll(List<DTO> dtos) {
        for (ListIterator<DTO> iterator = dtos.listIterator(); iterator.hasNext();) {
            DTO dto = iterator.next();
            ID id = extractId(dto);

            if (id == null) {
                Integer temporaryId = generateTemporaryId();
                dto = updateDtoWithId(dto, (ID) temporaryId);
                iterator.set(dto); // 리스트 내부 DTO도 갱신
                id = extractId(dto);
            }

            getRedisTemplate().opsForValue().set(getRedisKey(id), dto);
        }
        return dtos;
    }

    @Override
    public void deleteAllById(Iterable<ID> ids) {
        if (ids == null) {
            return;
        }
        ids.forEach(this::deleteCacheCascade);
    }

    // ==== 내부 헬퍼 메서드 ====

    protected final String getRedisKey(ID id) {
        return cacheKeyPrefix + ":" + id;
    }

    @SuppressWarnings("unchecked")
    protected final RedisTemplate<String, DTO> getRedisTemplate() {
        return (RedisTemplate<String, DTO>) applicationContext.getBean(redisTemplateBeanName);
    }

    @SuppressWarnings("unchecked")
    protected final ID extractId(DTO dto) {
        try {
            return (ID) idField.get(dto);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ID 필드에 접근할 수 없습니다: " + idField.getName(), e);
        }
    }

    /**
     * ID가 null일 경우 임시 음수 ID를 생성하여 저장
     */
    @SuppressWarnings("unchecked")
    public DTO save(DTO dto) {
        ID id = extractId(dto);

        // ID가 null이면 임시 음수 ID 생성
        if (id == null) {
            Integer temporaryId = generateTemporaryId();
            dto = updateDtoWithId(dto, (ID) temporaryId);
            id = extractId(dto);
        }

        getRedisTemplate().opsForValue().set(getRedisKey(id), dto);
        return dto;
    }

    @SuppressWarnings("unchecked")
    public final void saveUnchecked(Object dto) {
        save((DTO) dto);
    }

    /**
     * 기존 데이터를 불러와서 null이 아닌 값만 업데이트 (ID 제외)
     */
    public DTO update(DTO dto) {
        ID id = extractId(dto);

        if (id == null) {
            throw new IllegalArgumentException("update는 ID가 필수입니다. save를 사용하세요.");
        }

        DTO existingDto = getRedisTemplate().opsForValue().get(getRedisKey(id));
        if (existingDto != null) {
            dto = mergeDto(existingDto, dto);
        }

        getRedisTemplate().opsForValue().set(getRedisKey(id), dto);
        return dto;
    }

    /**
     * Entity의 필드를 다른 Entity의 null이 아닌 값으로 업데이트
     * 리플렉션을 사용하여 범용적으로 처리
     *
     * @param target 업데이트할 대상 Entity
     * @param source 데이터를 가져올 소스 Entity (null이 아닌 값만 복사)
     */
    public void mergeEntityFields(T target, T source) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("target과 source는 null일 수 없습니다.");
        }

        try {
            Class<?> entityClass = target.getClass();

            // 모든 필드를 순회하며 업데이트
            for (Field field : entityClass.getDeclaredFields()) {
                field.setAccessible(true);

                // ID 필드는 건너뛰기
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    continue;
                }

                // @ManyToOne, @OneToMany 등 관계 필드는 건너뛰기 (선택적)
                if (field.isAnnotationPresent(jakarta.persistence.ManyToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToMany.class) ||
                        field.isAnnotationPresent(jakarta.persistence.OneToOne.class) ||
                        field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)) {

                    // 관계 필드도 null이 아니면 업데이트
                    Object sourceValue = field.get(source);
                    if (sourceValue != null) {
                        field.set(target, sourceValue);
                    }
                    continue;
                }

                // source의 값이 null이 아니면 target에 설정
                Object sourceValue = field.get(source);
                if (sourceValue != null) {
                    field.set(target, sourceValue);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Entity 필드 병합 실패", e);
        }
    }

    /**
     * 기존 DTO와 새 DTO를 병합
     * 새 DTO의 null이 아닌 값들로 기존 DTO를 업데이트 (ID 제외)
     */
    private DTO mergeDto(DTO existingDto, DTO newDto) {
        try {
            for (Field field : dtoFields) {
                if (field.equals(idField)) {
                    continue;
                }

                Object newValue = field.get(newDto);
                if (newValue != null) {
                    field.set(existingDto, newValue);
                }
            }

            return existingDto;
        } catch (Exception e) {
            throw new RuntimeException("DTO 병합 실패: " + newDto, e);
        }
    }

    /**
     * Redis DECR을 사용하여 원자적으로 임시 음수 ID 생성
     * Redis의 카운터를 사용하므로 동시성 문제 없이 고유한 음수 ID 보장
     * 각 엔티티 타입별로 별도의 카운터 사용
     */
    private Integer generateTemporaryId() {
        // 엔티티 타입별로 별도의 카운터 키 사용 (예: "temporary:timetableplaceblock:counter")
        String counterKey = "temporary:" + cacheKeyPrefix + ":counter";

        // Redis의 DECR 명령: 키가 없으면 0에서 시작해서 -1 반환, 이후 -2, -3, ...
        Long counter = getRedisTemplate().opsForValue().decrement(counterKey);

        return counter.intValue();
    }

    /**
     * DTO의 ID 필드를 업데이트 (Record는 새 인스턴스 생성)
     */
    @SuppressWarnings("unchecked")
    private DTO updateDtoWithId(DTO dto, ID newId) {
        try {
            idField.set(dto, newId);
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO ID 업데이트 실패: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected final T convertToEntity(DTO dto) {
        try {
            // 필요한 Repository들을 자동으로 주입해서 Entity 변환
            Object[] parameters = buildEntityConverterParameters(dto);
            return (T) entityConverterMethod.invoke(dto, parameters);
        } catch (Exception e) {
            throw new RuntimeException("Entity 변환에 실패했습니다: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    protected final DTO convertToDto(T entity) {
        if (entity == null) {
            return null;
        }

        try {
            Method fromEntityMethod = dtoClass.getMethod("fromEntity", getEntityClass());
            return (DTO) fromEntityMethod.invoke(null, entity);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 fromEntity 메서드가 필요합니다.", e);
        } catch (Exception e) {
            throw new RuntimeException("Entity를 DTO로 변환하는 데 실패했습니다.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByParentId(ID parentId) {
        parentId = changeType(parentId);
        
        // Criteria API로 직접 쿼리 (Repository 필요 없음!)
        if (parentEntityFieldName != null && entityManager != null) {
            try {
                return loadEntitiesByCriteria(parentId);
            } catch (Exception e) {
                System.err.println("[SharedSync] Criteria API 로딩 실패: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // parentEntityFieldName이 없으면 (루트 엔티티) findAll 사용
        if (parentEntityFieldName == null && entityManager != null) {
            try {
                return loadAllEntitiesByCriteria();
            } catch (Exception e) {
                System.err.println("[SharedSync] Criteria API findAll 실패: " + e.getMessage());
            }
        }
        
        return Collections.emptyList();
    }
    
    /**
     * JPA Criteria API를 사용하여 parentId로 엔티티 조회
     * Repository에 메서드가 없어도 동작합니다!
     */
    @SuppressWarnings("unchecked")
    private List<T> loadEntitiesByCriteria(ID parentId) {
        Class<T> entityClass = getEntityClass();
        
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        Root<T> root = (Root<T>) query.from(entityClass);
        
        // parent.id = :parentId 조건 생성
        // 예: SELECT m FROM Memo m WHERE m.userShelfBook.id = :parentId
        Path<?> parentPath = root.get(parentEntityFieldName);
        Path<?> parentIdPath = parentPath.get("id");
        
        Predicate predicate = cb.equal(parentIdPath, parentId);
        query.where(predicate);
        
        return entityManager.createQuery(query).getResultList();
    }
    
    /**
     * JPA Criteria API를 사용하여 모든 엔티티 조회 (루트 엔티티용)
     */
    @SuppressWarnings("unchecked")
    private List<T> loadAllEntitiesByCriteria() {
        Class<T> entityClass = getEntityClass();
        
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = (CriteriaQuery<T>) cb.createQuery(entityClass);
        query.from(entityClass);
        
        return entityManager.createQuery(query).getResultList();
    }
    
    /**
     * JPA Criteria API를 사용하여 ID로 단일 엔티티 조회
     */
    @SuppressWarnings("unchecked")
    private T loadEntityByIdCriteria(ID id) {
        Class<T> entityClass = getEntityClass();
        
        // EntityManager.find() 사용 - 가장 효율적
        return entityManager.find(entityClass, id);
    }

    @Override
    public final List<DTO> loadFromDatabaseByParentId(ID parentId) {
        return loadEntitiesByParentId(parentId).stream()
                .map(this::convertToDto)
                .toList();
    }
    @SuppressWarnings("unchecked")
    public List<? extends CacheDto<?>> loadFromDatabaseByParentIdUnchecked(Object parentId) {
        return loadFromDatabaseByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public final DTO loadFromDatabaseById(ID id){
        id = changeType(id);

        try {
            // EntityManager.find() 사용 - Repository 필요 없음!
            T entity = loadEntityByIdCriteria(id);
            
            if (entity == null) {
                return null;
            }

            return convertToDto(entity);

        } catch (Exception e) {
            System.err.println("[SharedSync] ID로 데이터베이스 로딩 실패: " + id + " - " + e.getMessage());
            return null;
        }
    }

    private ID changeType(ID id){
        if(idClass.isInstance(id)){
            return id;
        }
        if(idClass.getSimpleName().equals("String")){
            return (ID) id.toString();
        }
        if(idClass.getSimpleName().equals("Integer")){
            return (ID) Integer.valueOf(id.toString());
        }
        if(idClass.getSimpleName().equals("Long")){
            return (ID) Long.valueOf(id.toString());
        }
        return null;
    }

    /**
     * ParentId로 캐시에서 Entity 리스트 조회
     * Redis에 이미 저장된 데이터를 조회 (DB가 아닌 캐시에서)
     */
    @Override
    public List<T> findByParentId(ID parentId) {
        List<DTO> dtos = findDtosByParentId(parentId);

        // Entity로 변환
        return dtos.stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * ParentId로 캐시에서 Entity 리스트 삭제
     * Redis에 저장된 해당 ParentId를 가진 모든 데이터를 삭제하고 삭제된 Entity 리스트 반환
     */
    @Override
    public List<T> deleteByParentId(ID parentId) {
        if (parentIdField == null) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }

        // 먼저 삭제할 DTO들을 조회
        List<DTO> dtosToDelete = findDtosByParentId(parentId);

        if (dtosToDelete.isEmpty()) {
            return Collections.emptyList();
        }

        // 하위 캐시 포함 삭제
        dtosToDelete.stream()
                .map(this::extractId)
                .forEach(this::deleteCacheCascade);

        // 삭제된 Entity 리스트 반환
        return dtosToDelete.stream()
                .map(this::convertToEntity)
                .toList();
    }

    /**
     * ParentId로 캐시에서 DTO 리스트 조회
     * Redis에 이미 저장된 DTO를 직접 반환 (Entity 변환 없음)
     */
    public List<DTO> findDtosByParentId(ID parentId) {
        if (parentIdField == null) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }

        // Redis에서 패턴으로 모든 키 찾기 (예: "plan:*")
        String pattern = cacheKeyPrefix + ":*";
        Set<String> keys = getRedisTemplate().keys(pattern);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        // 모든 DTO 가져오기
        List<DTO> allDtos = getRedisTemplate().opsForValue().multiGet(keys);
        if (allDtos == null) {
            return Collections.emptyList();
        }

        // parentId로 필터링 (널 안전성 및 타입-유연 비교 적용)
        return allDtos.stream()
                .filter(dto -> dto != null)
                .filter(dto -> {
                    try {
                        Object dtoParentId = parentIdField.get(dto);
                        // dto에 부모 ID가 비어있으면 후보에서 제외
                        if (dtoParentId == null || parentId == null) {
                            return false;
                        }

                        // 동일 타입이면 Objects.equals 사용
                        if (parentId.getClass().isInstance(dtoParentId) || dtoParentId.getClass().isInstance(parentId)) {
                            return Objects.equals(parentId, dtoParentId);
                        }

                        // 타입이 다르면 문자열로 비교 (예: "1" vs 1)
                        return parentId.toString().equals(dtoParentId.toString());
                    } catch (IllegalAccessException e) {
                        return false;
                    }
                })
                .toList();
    }

    private Object[] buildEntityConverterParameters(DTO dto) throws Exception {
        Object[] params = new Object[entityConverterRepositories.length];
        Class<?>[] parameterTypes = entityConverterMethod.getParameterTypes();
        Type[] genericParameterTypes = entityConverterMethod.getGenericParameterTypes();

        for (int i = 0; i < entityConverterRepositories.length; i++) {
            String repoName = entityConverterRepositories[i];
            Object repository = applicationContext.getBean(repoName);
            Class<?> paramType = parameterTypes[i];
            Type genericType = genericParameterTypes[i];

            // List 타입인 경우 특별 처리
            if (List.class.isAssignableFrom(paramType)) {
                // List<Tag>와 같은 경우
                Class<?> elementType = getListElementType(genericType);
                if (elementType != null) {
                    // DTO에서 ID 리스트 추출
                    List<?> idList = extractRelatedIdList(dto, elementType);
                    if (idList != null && !idList.isEmpty()) {
                        // 각 ID에 대해 엔티티 조회
                        List<Object> entities = new ArrayList<>();
                        for (Object id : idList) {
                            try {
                                Method getReferenceMethod = repository.getClass().getMethod("getReferenceById", Object.class);
                                Object entityRef = getReferenceMethod.invoke(repository, id);
                                entities.add(entityRef);
                            } catch (Exception e) {
                                // 개별 엔티티 조회 실패 시 건너뛰기
                            }
                        }
                        params[i] = entities;
                    } else {
                        params[i] = new ArrayList<>();
                    }
                } else {
                    params[i] = new ArrayList<>();
                }
            } else {
                // 단일 엔티티인 경우 기존 로직
                Object relatedId = extractRelatedId(dto, i);

                if (relatedId == null) {
                    params[i] = null;
                } else {
                    try {
                        Method getReferenceMethod = repository.getClass().getMethod("getReferenceById", Object.class);
                        Object entityRef = getReferenceMethod.invoke(repository, relatedId);
                        params[i] = entityRef;
                    } catch (Exception e) {
                        params[i] = null;
                    }
                }
            }
        }

        return params;
    }

    /**
     * 제네릭 타입에서 List의 요소 타입 추출
     */
    private Class<?> getListElementType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) genericType;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            if (typeArguments.length > 0 && typeArguments[0] instanceof Class) {
                return (Class<?>) typeArguments[0];
            }
        }
        return null;
    }

    /**
     * DTO에서 관련 ID 리스트 추출 (List<Tag> 등을 위해)
     */
    @SuppressWarnings("unchecked")
    private List<?> extractRelatedIdList(DTO dto, Class<?> elementType) {
        String elementSimpleName = elementType.getSimpleName();
        // 필드명 패턴: cache + EntityClassName + Ids (복수형)
        String patternFieldName = "cache" + elementSimpleName + "Ids";
        
        try {
            Field patternField = findFieldInHierarchy(dtoClass, patternFieldName);
            if (patternField != null) {
                patternField.setAccessible(true);
                Object value = patternField.get(dto);
                if (value instanceof List) {
                    return (List<?>) value;
                }
            }
        } catch (IllegalAccessException e) {
            // 무시
        }
        return null;
    }

    private Object extractRelatedId(DTO dto, int parameterIndex) {
        String repoName = entityConverterRepositories[parameterIndex];

        try {
            // Repository에서 엔티티 클래스 타입 가져오기
            Object repository = applicationContext.getBean(repoName);
            Class<?> entityClass = getEntityClassFromRepository(repository);

            if (entityClass == null) {
                return null;
            }

            // 엔티티에서 @Id 어노테이션이 붙은 필드 찾기
            String idFieldName = findIdFieldNameInEntity(entityClass);

            if (idFieldName == null) {
                return null;
            }

            // 1차 시도: DTO에서 같은 이름의 필드 찾기
            Field dtoIdField = findFieldInHierarchy(dtoClass, idFieldName);
            if (dtoIdField != null) {
                dtoIdField.setAccessible(true);
                Object idValue = dtoIdField.get(dto);
                if (idValue != null) {
                    return idValue;
                }
            }

            // 2차 시도: DTO에서 @ParentId(entityClass)가 붙은 필드 또는 @CacheId가 붙은 필드 찾기
            for (Field field : getAllFieldsInHierarchy(dtoClass)) {
                field.setAccessible(true);
                
                // @ParentId 어노테이션 확인 - 엔티티 클래스와 일치하는지
                ParentId parentIdAnnotation = field.getAnnotation(ParentId.class);
                if (parentIdAnnotation != null && parentIdAnnotation.value() == entityClass) {
                    Object idValue = field.get(dto);
                    return idValue;
                }
                
                // @CacheId 어노테이션 확인 - 해당 엔티티의 ID인 경우
                CacheId cacheIdAnnotation = field.getAnnotation(CacheId.class);
                if (cacheIdAnnotation != null) {
                    Object idValue = field.get(dto);
                    return idValue;
                }
            }

            // 3차 시도: 필드명 패턴 매칭 (cache + EntityClassName + Id)
            String entitySimpleName = entityClass.getSimpleName();
            String patternFieldName = "cache" + entitySimpleName + "Id";
            Field patternField = findFieldInHierarchy(dtoClass, patternFieldName);
            if (patternField != null) {
                patternField.setAccessible(true);
                return patternField.get(dto);
            }

            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("관련 ID 추출 실패: " + repoName, e);
        }
    }

    /**
     * 클래스 계층에서 모든 필드 가져오기
     */
    private List<Field> getAllFieldsInHierarchy(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * Repository에서 엔티티 클래스 타입 추출
     */
    private Class<?> getEntityClassFromRepository(Object repository) {
        try {
            // Spring Data JPA Repository는 프록시 객체이므로 인터페이스를 찾아야 함
            Class<?>[] interfaces = repository.getClass().getInterfaces();

            for (Class<?> iface : interfaces) {
                // Repository 인터페이스 찾기
                if (iface.getName().endsWith("Repository")) {
                    // 인터페이스의 제네릭 타입 추출
                    Type[] genericInterfaces = iface.getGenericInterfaces();
                    for (Type genericInterface : genericInterfaces) {
                        if (genericInterface instanceof ParameterizedType) {
                            ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
                            Type rawType = parameterizedType.getRawType();

                            // JpaRepository 인터페이스 확인
                            if (rawType.getTypeName().contains("JpaRepository")) {
                                Type[] typeArguments = parameterizedType.getActualTypeArguments();
                                if (typeArguments.length > 0) {
                                    if (typeArguments[0] instanceof Class) {
                                        return (Class<?>) typeArguments[0]; // 첫 번째 제네릭 타입이 엔티티
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 실패 시 null 반환
        }
        return null;
    }

    /**
     * 엔티티 클래스에서 @Id 어노테이션이 붙은 필드 이름 찾기
     */
    private String findIdFieldNameInEntity(Class<?> entityClass) {
        // 모든 필드 순회
        for (Field field : entityClass.getDeclaredFields()) {
            // @Id 어노테이션 확인
            if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                return field.getName();
            }
        }
        return null;
    }

    private Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private String generateRepositoryName() {
        String entityName = dtoClass.getSimpleName().replace("Dto", "");
        return Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1) + "Repository";
    }

    private String generateLoadMethodName() {
        if (parentIdField == null) return "findAll";
        String parentFieldName = parentIdField.getName().replace("Id", "");
        String capitalizedParentName = Character.toUpperCase(parentFieldName.charAt(0)) + parentFieldName.substring(1);
        return "findBy" + capitalizedParentName + capitalizedParentName + "Id";
    }

    private Method findLoadMethod(Object repository, ID parentId) throws NoSuchMethodException {
        Class<?> repoClass = repository.getClass();
        Class<?> expectedParam = parentId != null ? parentId.getClass() : Object.class;

        // 1) try exact match
        try {
            return repoClass.getMethod(loadMethodName, expectedParam);
        } catch (NoSuchMethodException ignored) {
        }

        // helper to check primitive/wrapper compatibility
        java.util.function.BiPredicate<Class<?>, Class<?>> compatible = (expected, actual) -> {
            if (actual.isAssignableFrom(expected)) return true;
            // wrapper <-> primitive
            if (expected == Long.class && actual == long.class) return true;
            if (expected == Integer.class && actual == int.class) return true;
            if (expected == Short.class && actual == short.class) return true;
            if (expected == Byte.class && actual == byte.class) return true;
            if (expected == Double.class && actual == double.class) return true;
            if (expected == Float.class && actual == float.class) return true;
            if (expected == Character.class && actual == char.class) return true;
            if (expected == Boolean.class && actual == boolean.class) return true;
            // also allow the reverse (primitive method parameter vs wrapper expected)
            if (actual == Long.class && expected == long.class) return true;
            if (actual == Integer.class && expected == int.class) return true;
            if (actual == Short.class && expected == short.class) return true;
            if (actual == Byte.class && expected == byte.class) return true;
            if (actual == Double.class && expected == double.class) return true;
            if (actual == Float.class && expected == float.class) return true;
            if (actual == Character.class && expected == char.class) return true;
            if (actual == Boolean.class && expected == boolean.class) return true;
            return false;
        };

        // 2) search public methods on the proxy/impl class
        for (Method m : repoClass.getMethods()) {
            if (!m.getName().equals(loadMethodName)) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> actualParam = m.getParameterTypes()[0];
            if (compatible.test(expectedParam, actualParam)) {
                return m;
            }
        }

        // 3) search declared methods on implemented interfaces (often repository interfaces)
        for (Class<?> iface : repoClass.getInterfaces()) {
            for (Method m : iface.getMethods()) {
                if (!m.getName().equals(loadMethodName)) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> actualParam = m.getParameterTypes()[0];
                if (compatible.test(expectedParam, actualParam)) {
                    return m;
                }
            }
        }

        // 4) Fallback: try alternative method name patterns commonly used in Spring Data JPA
        //    e.g., findByUserShelfBookId -> findByUserShelfBook_Id or findByUserShelfBookUserShelfBookId
        List<String> alternativeNames = generateAlternativeMethodNames(loadMethodName);
        for (String altName : alternativeNames) {
            for (Method m : repoClass.getMethods()) {
                if (!m.getName().equals(altName)) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> actualParam = m.getParameterTypes()[0];
                if (compatible.test(expectedParam, actualParam)) {
                    return m;
                }
            }
            for (Class<?> iface : repoClass.getInterfaces()) {
                for (Method m : iface.getMethods()) {
                    if (!m.getName().equals(altName)) continue;
                    if (m.getParameterCount() != 1) continue;
                    Class<?> actualParam = m.getParameterTypes()[0];
                    if (compatible.test(expectedParam, actualParam)) {
                        return m;
                    }
                }
            }
        }

        // 5) Last resort: find ANY method that returns List and takes single compatible param
        for (Method m : repoClass.getMethods()) {
            if (!m.getName().startsWith("findBy")) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> actualParam = m.getParameterTypes()[0];
            if (!compatible.test(expectedParam, actualParam)) continue;
            // check return type is List or Collection
            if (java.util.List.class.isAssignableFrom(m.getReturnType()) ||
                java.util.Collection.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }

        // Nothing matched - throw informative exception
        throw new NoSuchMethodException("Load method '" + loadMethodName + "' with compatible parameter not found on repository " + repoClass.getName());
    }

    /**
     * Generate alternative method name patterns for fallback lookup.
     * e.g., "findByUserShelfBookId" -> ["findByUserShelfBook_Id", "findByUserShelfBookUserShelfBookId"]
     */
    private List<String> generateAlternativeMethodNames(String methodName) {
        List<String> alternatives = new ArrayList<>();
        
        // Pattern: findBy{Entity}Id -> findBy{Entity}_Id (underscore variant)
        if (methodName.endsWith("Id") && methodName.length() > 8) {
            String withUnderscore = methodName.substring(0, methodName.length() - 2) + "_Id";
            alternatives.add(withUnderscore);
        }
        
        // Pattern: findBy{Entity}Id -> findBy{Entity}{Entity}Id (Spring Data nested property)
        // Extract entity name from findBy{Entity}Id
        if (methodName.startsWith("findBy") && methodName.endsWith("Id")) {
            String entityPart = methodName.substring(6, methodName.length() - 2); // e.g., "UserShelfBook"
            if (!entityPart.isEmpty()) {
                // findByUserShelfBookUserShelfBookId pattern
                alternatives.add("findBy" + entityPart + entityPart + "Id");
                // findByUserShelfBook_UserShelfBookId pattern  
                alternatives.add("findBy" + entityPart + "_" + entityPart + "Id");
            }
        }
        
        return alternatives;
    }

    private Method resolveFindByIdMethod(Object repository) throws NoSuchMethodException {
        for (Method method : repository.getClass().getMethods()) {
            if ("findById".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException("findById method not found on repository " + repository.getClass());
    }

    @SuppressWarnings("unchecked")
    private Class<T> getEntityClass() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
            return (Class<T>) typeArgs[0]; // Entity는 첫 번째 타입 파라미터
        }
        throw new IllegalStateException("Entity 클래스를 추출할 수 없습니다.");
    }

    private Field findFieldWithAnnotation(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotationClass)) {
                return field;
            }
        }
        return null;
    }

    private Method findMethodWithAnnotation(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationClass)) {
                return method;
            }
        }
        return null;
    }

    private Field locateEntityIdField(Class<?> entityClass) {
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public DTO findDtoById(ID id){
        return getRedisTemplate().opsForValue().get(getRedisKey(id));
    }
    public List<DTO> findDtoListByParentId(ID parentId){
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

    public void deleteCacheById(ID id) {
        if (id == null) {
            return;
        }
        deleteCacheCascade(id);
    }

    public void deleteCacheByParentId(ID parentId) {
        if (parentIdField == null || parentId == null) {
            return;
        }
        removeEntriesByParentInternal(parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByParentIdUnchecked(Object parentId) {
        if (parentId == null) {
            return;
        }
        deleteCacheByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteCacheByIdUnchecked(Object id) {
        if (id == null) {
            return;
        }
        deleteCacheById((ID) id);
    }

    private void deleteCacheCascade(ID id) {
        if (id == null) {
            return;
        }

        propagateParentDeletion(id);
        getRedisTemplate().delete(getRedisKey(id));
    }

    @SuppressWarnings("unchecked")
    private void propagateParentDeletion(Object parentIdObject) {
        if (parentIdObject == null) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories =
                (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext.getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClass == null) {
                continue;
            }
            if (!repository.parentEntityClass.isAssignableFrom(entityClass)) {
                continue;
            }
            repository.removeEntriesByParentInternal(parentIdObject);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeEntriesByParentInternal(Object parentIdObject) {
        if (parentIdField == null || parentIdObject == null) {
            return;
        }
        if (!parentIdField.getType().isInstance(parentIdObject)) {
            return;
        }

        ID parentId = (ID) parentIdObject;
        List<DTO> dtos = findDtosByParentId(parentId);
        if (dtos.isEmpty()) {
            return;
        }

        for (DTO dto : dtos) {
            ID childId = extractId(dto);
            deleteCacheCascade(childId);
        }
    }

    @SuppressWarnings("unchecked")
    private void syncToDatabaseByParentIdInternal(Object parentIdObject) {
        if (parentIdField == null || parentIdObject == null) {
            return;
        }
        if (!parentIdField.getType().isInstance(parentIdObject)) {
            return;
        }

        ID parentId = (ID) parentIdObject;
        syncToDatabaseByParentId(parentId);
    }

    // ==== 동기화 메소드 ====

    public static void syncHierarchyToDatabaseByRootId(int rootId){
        //
    }
    public static void syncHierarchyToDatabaseByRootId(String rootId){

    }

    public DTO syncToDatabaseByDto(DTO dto) {
        if (dto == null) {
            return null;
        }
        // 부모가 없을 때
        if (parentIdField == null) {
            return saveToDatabase(dto);
        }
        // 부모가 있을 때
        Object parentIdValue = getParentIdValue(dto);
        if (parentIdValue instanceof Number number && number.longValue() <0) {
            System.out.println("부모키가 음수라 저장할 수 없습니다");
            return null;
        }
        return saveToDatabase(dto);
    }

    @SuppressWarnings("unchecked")
    public DTO syncToDatabaseByDtoUnchecked(Object dto) {
        return syncToDatabaseByDto((DTO) dto);
    }

    /**
     * 캐시에 존재하는 ParentId 하위 DTO들을 DB와 동기화하며, 캐시에 없어진 엔티티는 DB에서도 삭제합니다.
     */
    public List<DTO> syncToDatabaseByParentId(ID parentId) {
        if (parentIdField == null) {
            throw new UnsupportedOperationException("ParentId 필드가 없습니다.");
        }
        if (parentId == null) {
            return Collections.emptyList();
        }
        if (parentId instanceof Number number && number.longValue() < 0L) {
            return Collections.emptyList(); // 아직 영속화되지 않은 부모
        }

        List<DTO> cachedDtos = findDtoListByParentId(parentId);
        if (!cachedDtos.isEmpty()) {
            cachedDtos.forEach(this::syncToDatabaseByDto);
        }

        List<DTO> refreshedDtos = findDtoListByParentId(parentId);
        Set<ID> cachedPersistentIds = refreshedDtos.stream()
                .map(this::extractId)
                .filter(Objects::nonNull)
                .filter(id -> !isTemporaryId(id))
                .collect(Collectors.toSet());

        List<T> persistedEntities = loadEntitiesByParentId(parentId);
        if (persistedEntities == null || persistedEntities.isEmpty()) {
            return Collections.emptyList();
        }

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

    @SuppressWarnings("unchecked")
    public List<DTO> syncToDatabaseByParentIdUnchecked(Object parentId) {
        return syncToDatabaseByParentId((ID) parentId);
    }

    @SuppressWarnings("unchecked")
    public void deleteEntitiesNotInCache(Object parentId, Set<Object> persistentIds) {
        if (parentIdField == null || parentId == null) {
            return;
        }
        if (!parentIdField.getType().isInstance(parentId)) {
            return;
        }

        ID typedParentId = (ID) parentId;
        List<T> persistedEntities = loadEntitiesByParentId(typedParentId);
        if (persistedEntities == null || persistedEntities.isEmpty()) {
            return;
        }

        Set<ID> allowedIds = persistentIds == null ? Collections.emptySet()
                : persistentIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> entityIdField.getType().isInstance(id))
                .map(id -> (ID) id)
                .collect(Collectors.toSet());

        List<T> targets = persistedEntities.stream()
                .filter(entity -> {
                    ID entityId = extractEntityId(entity);
                    return entityId != null && !allowedIds.contains(entityId);
                })
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            return;
        }

        handleChildCleanupBeforeDelete(targets);
        deleteAllEntities(targets);
    }

    @SuppressWarnings("unchecked")
    private void handleChildCleanupBeforeDelete(List<T> entitiesToDelete) {
        if (entitiesToDelete == null || entitiesToDelete.isEmpty()) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories =
                (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext.getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();

        for (T entity : entitiesToDelete) {
            ID parentId = extractEntityId(entity);
            if (parentId == null) {
                continue;
            }

            for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
                if (repository.parentEntityClass == null) {
                    continue;
                }
                if (!repository.parentEntityClass.isAssignableFrom(entityClass)) {
                    continue;
                }
                repository.syncToDatabaseByParentIdInternal(parentId);
                repository.removeEntriesByParentInternal(parentId);
            }
        }
    }

    @SuppressWarnings("null")
    private DTO saveToDatabase(DTO dto) {
        T entity = convertToEntity(dto);

        ID previousId = extractEntityId(entity);
        boolean hasPersistentId = previousId != null && !isTemporaryId(previousId);

        if (!hasPersistentId) {
            setEntityId(entity, null);
        }

        T entityToSave = entity;
        if (hasPersistentId) {
            ID persistedId = Objects.requireNonNull(previousId);
            T origin = entityManager.find(getEntityClass(), persistedId);
            if (origin != null) {
                mergeEntityFields(origin, entity);
                entityToSave = origin;
            }
        }

        // EntityManager로 저장 (persist 또는 merge)
        T savedEntity = saveEntity(entityToSave);

        DTO updatedDto = convertToDto(savedEntity);
        ID cacheId = extractId(updatedDto);

        if (cacheId != null) {
            String cacheKey = getRedisKey(cacheId);
            DTO dtoToCache = Objects.requireNonNull(updatedDto);
            getRedisTemplate().opsForValue().set(cacheKey, dtoToCache);
        }

        // 새로 영속화된 ID를 모든 하위 캐시에 전파
        if (isTemporaryId(previousId) && !isTemporaryId(cacheId)) {
            propagateParentIdChange(previousId, cacheId);
        }
        if (previousId != null && !Objects.equals(previousId, cacheId)) {
            String staleKey = getRedisKey(previousId);
            getRedisTemplate().delete(staleKey);
        }
        return updatedDto;
    }
    
    /**
     * EntityManager를 사용하여 엔티티 저장 (persist 또는 merge)
     */
    private T saveEntity(T entity) {
        ID id = extractEntityId(entity);
        if (id == null) {
            // 새 엔티티 - persist
            entityManager.persist(entity);
            return entity;
        } else {
            // 기존 엔티티 - merge
            return entityManager.merge(entity);
        }
    }
    
    /**
     * EntityManager를 사용하여 여러 엔티티 삭제
     */
    private void deleteAllEntities(List<T> entities) {
        for (T entity : entities) {
            T managed = entityManager.contains(entity) ? entity : entityManager.merge(entity);
            entityManager.remove(managed);
        }
    }

    public boolean isParentIdFieldPresent() {
        return parentIdField != null;
    }

    public boolean isParentEntityOf(Class<?> potentialParentEntity) {
        return parentEntityClass != null && parentEntityClass.isAssignableFrom(potentialParentEntity);
    }

    public Class<?> getEntityType() {
        return getEntityClass();
    }

    @SuppressWarnings("unchecked")
    public Object extractIdUnchecked(Object dto) {
        return extractId((DTO) dto);
    }

    public boolean isPersistentId(Object id) {
        return id != null && !isTemporaryId(id);
    }


    private Object getParentIdValue(DTO dto) {
        if (parentIdField == null) {
            return null;
        }
        try {
            return parentIdField.get(dto);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("ParentId 필드 접근 실패", e);
        }
    }

    private boolean isTemporaryId(Object id) {
        if (id == null) {
            return false;
        }
        if (id instanceof Number number) {
            return number.longValue() < 0L;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void propagateParentIdChange(ID temporaryParentId, ID persistedParentId) {
        if (temporaryParentId == null || persistedParentId == null) {
            return;
        }

        Map<String, AutoCacheRepository<?, ?, ?>> repositories =
                (Map<String, AutoCacheRepository<?, ?, ?>>) (Map<?, ?>) applicationContext.getBeansOfType(AutoCacheRepository.class);
        Class<T> entityClass = getEntityClass();
        for (AutoCacheRepository<?, ?, ?> repository : repositories.values()) {
            if (repository == this) {
                continue;
            }
            if (repository.parentEntityClass == null) {
                continue;
            }
            if (!repository.parentEntityClass.isAssignableFrom(entityClass)) {
                continue;
            }
            repository.updateParentReferenceInternal(temporaryParentId, persistedParentId);
        }
    }

    @SuppressWarnings("null")
    private void updateParentReferenceInternal(Object oldParentId, Object newParentId) {
        if (parentEntityClass == null) {
            return;
        }
        if (parentIdField == null) {
            return;
        }
        if (oldParentId == null || newParentId == null) {
            return;
        }
        if (!parentIdField.getType().isInstance(oldParentId) || !parentIdField.getType().isInstance(newParentId)) {
            return;
        }

        String pattern = cacheKeyPrefix + ":*";
        Set<String> keys = getRedisTemplate().keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return;
        }

        List<DTO> dtos = getRedisTemplate().opsForValue().multiGet(keys);
        if (dtos == null || dtos.isEmpty()) {
            return;
        }

        for (DTO dto : dtos) {
            if (dto == null) {
                continue;
            }
            try {
                Object parentValue = parentIdField.get(dto);
                if (Objects.equals(parentValue, oldParentId)) {
                    DTO updated = updateDtoParentId(dto, newParentId);
                    ID dtoId = extractId(updated);
                    if (dtoId != null) {
                        String redisKey = getRedisKey(dtoId);
                        getRedisTemplate().opsForValue().set(redisKey, Objects.requireNonNull(updated));
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("ParentId 필드 접근 실패", e);
            }
        }
    }

    private DTO updateDtoParentId(DTO dto, Object newParentId) {
        if (parentIdField == null) {
            return dto;
        }

        try {
            parentIdField.set(dto, newParentId);
            return dto;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("DTO 부모 ID 업데이트 실패", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ID extractEntityId(T entity) {
        if (entity == null) {
            return null;
        }
        try {
            return (ID) entityIdField.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 접근 실패", e);
        }
    }

    private void setEntityId(T entity, Object value) {
        if (entity == null) {
            return;
        }
        try {
            entityIdField.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("엔티티 ID 설정 실패", e);
        }
    }

}