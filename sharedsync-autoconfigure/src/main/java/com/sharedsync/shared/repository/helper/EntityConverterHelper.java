package com.sharedsync.shared.repository.helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationContext;

import com.sharedsync.shared.annotation.ParentId;
import com.sharedsync.shared.annotation.TableName;
import com.sharedsync.shared.dto.CacheDto;

import jakarta.persistence.EntityManager;

public class EntityConverterHelper<T, ID, DTO extends CacheDto<ID>> {

    private final Class<DTO> dtoClass;
    private final Class<T> entityClass;
    private final Method entityConverterMethod;
    private final ApplicationContext applicationContext;
    private final EntityManager entityManager;
    private final List<Field> dtoFields;

    public EntityConverterHelper(
            Class<DTO> dtoClass,
            Class<T> entityClass,
            Method entityConverterMethod,
            ApplicationContext applicationContext,
            EntityManager entityManager,
            List<Field> dtoFields) {
        this.dtoClass = dtoClass;
        this.entityClass = entityClass;
        this.entityConverterMethod = entityConverterMethod;
        this.applicationContext = applicationContext;
        this.entityManager = entityManager;
        this.dtoFields = dtoFields;
    }

    @SuppressWarnings("unchecked")
    public T convertToEntity(DTO dto) {
        try {
            Object[] parameters = buildEntityConverterParameters(dto);
            return (T) entityConverterMethod.invoke(dto, parameters);
        } catch (Exception e) {
            throw new RuntimeException("Entity 변환에 실패했습니다: " + dto, e);
        }
    }

    @SuppressWarnings("unchecked")
    public DTO convertToDto(T entity) {
        if (entity == null) {
            return null;
        }

        try {
            Method fromEntityMethod = dtoClass.getMethod("fromEntity", entityClass);
            return (DTO) fromEntityMethod.invoke(null, entity);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(dtoClass.getSimpleName() + "에 fromEntity 메서드가 필요합니다.", e);
        } catch (Exception e) {
            throw new RuntimeException("Entity를 DTO로 변환하는 데 실패했습니다.", e);
        }
    }

    private Object[] buildEntityConverterParameters(DTO dto) {
        Class<?>[] paramTypes = entityConverterMethod.getParameterTypes();
        Type[] genericParamTypes = entityConverterMethod.getGenericParameterTypes();
        Object[] params = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];

            // 1. EntityManager
            if (paramType.equals(EntityManager.class)) {
                params[i] = entityManager;
                continue;
            }

            // 2. ApplicationContext
            if (paramType.equals(ApplicationContext.class)) {
                params[i] = applicationContext;
                continue;
            }

            // 3. Other repositories (Recursive check might be needed but simple bean lookup for now)
            // Note: In AutoCacheRepository it was Assignment check for AutoCacheRepository.class
            // We'll keep that logic.
            if ("com.sharedsync.shared.repository.AutoCacheRepository".equals(paramType.getSuperclass() != null ? paramType.getSuperclass().getName() : "")) {
                params[i] = applicationContext.getBean(paramType);
                continue;
            }

            // 4. Resolve related entities
            Class<?> expectedEntityClass = null;
            if (List.class.isAssignableFrom(paramType)) {
                expectedEntityClass = getListElementType(genericParamTypes[i]);
                
                if (expectedEntityClass != null) {
                    List<?> idList = extractRelatedIdList(dto, expectedEntityClass);
                    if (idList != null && !idList.isEmpty()) {
                        List<Object> entities = new ArrayList<>();
                        for (Object id : idList) {
                            try {
                                Field relatedIdField = RepositoryReflectionUtils.locateEntityIdField(expectedEntityClass);
                                Class<?> relatedIdType = relatedIdField != null ? relatedIdField.getType() : null;
                                Object normalizedId = RepositoryReflectionUtils.convertIdToType(relatedIdType, id);
                                Object ref = entityManager.getReference(expectedEntityClass, normalizedId);
                                entities.add(ref);
                            } catch (Exception e) {
                                // skip missing/invalid ids
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
                expectedEntityClass = paramType;
                Object relatedId = extractRelatedId(dto, i);
                if (relatedId == null) {
                    params[i] = null;
                } else {
                    try {
                        Field relatedIdField = RepositoryReflectionUtils.locateEntityIdField(expectedEntityClass);
                        Class<?> relatedIdType = relatedIdField != null ? relatedIdField.getType() : null;
                        Object normalized = RepositoryReflectionUtils.convertIdToType(relatedIdType, relatedId);
                        params[i] = entityManager.getReference(expectedEntityClass, normalized);
                    } catch (Exception e) {
                        params[i] = null;
                    }
                }
            }
        }

        return params;
    }

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

    private List<?> extractRelatedIdList(DTO dto, Class<?> elementType) {
        String tableName = RepositoryReflectionUtils.getTableName(elementType);
        for (Field field : dtoFields) {
            TableName tableNameAnnotation = field.getAnnotation(TableName.class);
            if (tableNameAnnotation != null && tableNameAnnotation.value().equalsIgnoreCase(tableName)) {
                try {
                    Object value = field.get(dto);
                    if (value instanceof List) {
                        return (List<?>) value;
                    }
                } catch (IllegalAccessException e) {}
            }
        }
        return null;
    }

    private Object extractRelatedId(DTO dto, int parameterIndex) {
        try {
            Class<?>[] paramTypes = entityConverterMethod.getParameterTypes();
            Type[] genericParamTypes = entityConverterMethod.getGenericParameterTypes();
            Class<?> targetEntityClass = null;
            if (parameterIndex < paramTypes.length) {
                Class<?> paramType = paramTypes[parameterIndex];
                if (List.class.isAssignableFrom(paramType)) {
                    targetEntityClass = getListElementType(genericParamTypes[parameterIndex]);
                } else {
                    targetEntityClass = paramType;
                }
            }

            if (targetEntityClass == null) {
                return null;
            }

            // 0순위: @TableName 어노테이션 매칭
            String tableName = RepositoryReflectionUtils.getTableName(targetEntityClass);
            for (Field field : dtoFields) {
                TableName tableNameAnnotation = field.getAnnotation(TableName.class);
                if (tableNameAnnotation != null && tableNameAnnotation.value().equalsIgnoreCase(tableName)) {
                    Object val = field.get(dto);
                    if (val != null) return val;
                }
            }

            // 1순위: DTO에서 @ParentId(entityClass)가 붙은 필드 찾기
            for (Field field : dtoFields) {
                ParentId parentIdAnnotation = field.getAnnotation(ParentId.class);
                if (parentIdAnnotation != null && parentIdAnnotation.value() == targetEntityClass) {
                    Object idValue = field.get(dto);
                    if (idValue != null) return idValue;
                }
            }

            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("관련 ID 추출 실패: parameterIndex=" + parameterIndex, e);
        }
    }
}
