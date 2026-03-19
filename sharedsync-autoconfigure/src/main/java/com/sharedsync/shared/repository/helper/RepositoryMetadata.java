package com.sharedsync.shared.repository.helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.annotation.CacheEntity;
import com.sharedsync.shared.annotation.CacheId;
import com.sharedsync.shared.annotation.EntityConverter;
import com.sharedsync.shared.annotation.IgnoreShared;
import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.dto.CacheDto;

/**
 * AutoCacheRepository의 제네릭 타입과 어노테이션으로부터 리플렉션으로 추출한 메타데이터를 보관합니다.
 * 생성자에서 한 번만 초기화되며, 이후 불변(immutable)으로 유지됩니다.
 */
@SuppressWarnings("unchecked")
public class RepositoryMetadata<T, ID, DTO extends CacheDto<ID>> {

    public final Class<T> entityClass;
    public final Class<DTO> dtoClass;
    public final Class<ID> idClass;
    public final String cacheKeyPrefix;
    public final String redisTemplateBeanName;
    public final Field idField;
    public final List<Field> parentIdFields;
    public final Map<Field, Class<?>> parentEntityClassMap;
    public final Method entityConverterMethod;
    public final Field entityIdField;
    public final List<Field> ignoredEntityFields;
    public final List<Field> dtoFields;
    public final String sequenceName;
    public final int allocationSize;
    public final boolean useIdPool;

    public RepositoryMetadata(Class<?> repositorySubclass) {
        // 제네릭 타입 파라미터 추출
        Type superClass = repositorySubclass.getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType)) {
            throw new IllegalStateException("DTO 클래스를 추출할 수 없습니다.");
        }
        Type[] typeArgs = ((ParameterizedType) superClass).getActualTypeArguments();
        this.entityClass = (Class<T>) typeArgs[0];
        this.dtoClass = (Class<DTO>) typeArgs[2];

        // @Cache 어노테이션에서 캐시 키 프리픽스 추출
        Cache cacheAnnotation = dtoClass.getAnnotation(Cache.class);
        if (cacheAnnotation == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @Cache 어노테이션이 없습니다.");
        }
        String annotationKeyType = cacheAnnotation.keyType();
        this.cacheKeyPrefix = (annotationKeyType == null || annotationKeyType.isEmpty())
                ? dtoClass.getSimpleName().replace("Dto", "").toLowerCase()
                : annotationKeyType.toLowerCase();

        // RedisTemplate 빈 이름 자동 유도
        String entitySimpleName = dtoClass.getSimpleName().replace("Dto", "");
        this.redisTemplateBeanName = Character.toLowerCase(entitySimpleName.charAt(0))
                + entitySimpleName.substring(1) + "Redis";

        // @CacheId 필드
        this.idField = RepositoryReflectionUtils.findFieldWithAnnotation(dtoClass, CacheId.class);
        if (this.idField == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @CacheId 어노테이션이 붙은 필드가 없습니다.");
        }
        this.idField.setAccessible(true);

        // @ParentId 필드 및 부모 엔티티 클래스 맵
        List<Field> parentIds = new ArrayList<>();
        Map<Field, Class<?>> parentEntityMap = new HashMap<>();
        for (Field field : dtoClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(ParentId.class)) {
                field.setAccessible(true);
                parentIds.add(field);
                ParentId ann = field.getAnnotation(ParentId.class);
                if (ann != null && ann.value() != Object.class) {
                    parentEntityMap.put(field, ann.value());
                }
            }
        }
        this.parentIdFields = Collections.unmodifiableList(parentIds);
        this.parentEntityClassMap = Collections.unmodifiableMap(parentEntityMap);

        // @EntityConverter 메서드
        this.entityConverterMethod = RepositoryReflectionUtils.findMethodWithAnnotation(dtoClass, EntityConverter.class);
        if (this.entityConverterMethod == null) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 @EntityConverter 어노테이션이 붙은 메서드가 없습니다.");
        }
        this.entityConverterMethod.setAccessible(true);

        // 엔티티 @Id 필드
        Field detectedEntityIdField = RepositoryReflectionUtils.locateEntityIdField(entityClass);
        if (detectedEntityIdField == null) {
            throw new IllegalStateException("@Id 필드를 찾을 수 없습니다: " + entityClass.getSimpleName());
        }
        detectedEntityIdField.setAccessible(true);
        this.entityIdField = detectedEntityIdField;
        this.idClass = (Class<ID>) detectedEntityIdField.getType();

        // @IgnoreShared 필드 (동기화 시 DB 원본 값을 보존할 엔티티 필드)
        List<Field> ignored = new ArrayList<>();
        for (Field f : entityClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(IgnoreShared.class)) {
                f.setAccessible(true);
                ignored.add(f);
            }
        }
        this.ignoredEntityFields = Collections.unmodifiableList(ignored);

        // DTO 인스턴스 필드 목록 (static 제외)
        this.dtoFields = Arrays.stream(dtoClass.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> field.setAccessible(true))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));

        // ID Pool 설정
        CacheEntity cacheEntityAnnotation = entityClass.getAnnotation(CacheEntity.class);
        if (cacheEntityAnnotation != null && RepositoryReflectionUtils.isNumericIdType(this.idClass)) {
            this.allocationSize = cacheEntityAnnotation.allocationSize();
            this.sequenceName = RepositoryReflectionUtils.deriveSequenceName(entityClass, this.entityIdField);
            this.useIdPool = true;
        } else {
            this.sequenceName = null;
            this.allocationSize = cacheEntityAnnotation != null ? cacheEntityAnnotation.allocationSize() : 0;
            this.useIdPool = false;
        }
    }
}
