package com.sharedsync.shared.repository.helper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

public class CriteriaQueryBuilder<T, ID> {

    private final EntityManager entityManager;
    private final Class<T> entityClass;
    private final Field entityIdField;
    private final Map<Field, Class<?>> parentEntityClassMap;
    private final List<Field> dtoFields;

    public CriteriaQueryBuilder(EntityManager entityManager, Class<T> entityClass, Field entityIdField,
                                Map<Field, Class<?>> parentEntityClassMap, List<Field> dtoFields) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.entityIdField = entityIdField;
        this.parentEntityClassMap = parentEntityClassMap;
        this.dtoFields = dtoFields;
    }

    @SuppressWarnings("unchecked")
    public List<T> loadEntitiesByCriteria(Object parentId, Class<?> targetParentClass) {
        if (parentEntityClassMap.isEmpty() || entityManager == null) {
            return loadAllEntitiesByCriteria();
        }

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        Root<T> root = query.from(entityClass);

        List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
        for (Class<?> parentClass : parentEntityClassMap.values()) {
            if (targetParentClass != null && !parentClass.equals(targetParentClass)) {
                continue;
            }

            for (Field field : RepositoryReflectionUtils.getAllFieldsInHierarchy(entityClass)) {
                if (field.getType().isAssignableFrom(parentClass)) {
                    try {
                        Field pIdField = RepositoryReflectionUtils.locateEntityIdField(parentClass);
                        if (pIdField == null) continue;

                        String idFieldName = pIdField.getName();
                        Class<?> pIdType = pIdField.getType();

                        Object normalizedParentId = RepositoryReflectionUtils.convertIdToType(pIdType, parentId);

                        jakarta.persistence.criteria.Path<?> parentPath = root.get(field.getName());
                        jakarta.persistence.criteria.Path<?> parentIdPath = parentPath.get(idFieldName);
                        predicates.add(cb.equal(parentIdPath, normalizedParentId));
                    } catch (Exception e) {
                        System.err.println("[SharedSync][WARN] Failed to build predicate for field " + field.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        if (predicates.isEmpty()) {
            return Collections.emptyList();
        }

        if (predicates.size() == 1) {
            query.where(predicates.get(0));
        } else {
            query.where(cb.or(predicates.toArray(new jakarta.persistence.criteria.Predicate[0])));
        }

        return entityManager.createQuery(query).getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<T> loadAllEntitiesByCriteria() {
        if (entityManager == null) return Collections.emptyList();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(entityClass);
        query.from(entityClass);
        return entityManager.createQuery(query).getResultList();
    }

    public T loadEntityByIdCriteria(ID id) {
        if (entityManager == null) return null;

        try {
            List<String> relationsToFetch = new ArrayList<>();

            for (Field dtoField : dtoFields) {
                String dtoFieldName = dtoField.getName();
                if (dtoFieldName == null) continue;
                if (dtoFieldName.endsWith("Id")) {
                    String entitySimple = dtoFieldName.substring(0, dtoFieldName.length() - 2);
                    if (entitySimple.isEmpty()) continue;
                    String candidate = Character.toLowerCase(entitySimple.charAt(0)) + entitySimple.substring(1);

                    for (Field f : RepositoryReflectionUtils.getAllFieldsInHierarchy(entityClass)) {
                        String relationName = null;

                        if (f.getName().equals(candidate) || f.getType().getSimpleName().equals(entitySimple)) {
                            relationName = f.getName();
                        }

                        try {
                            jakarta.persistence.JoinColumn jc = f.getAnnotation(jakarta.persistence.JoinColumn.class);
                            if (jc != null) {
                                String jcName = jc.name();
                                if (jcName != null && !jcName.isBlank()) {
                                    if (jcName.toLowerCase().contains(candidate.toLowerCase())) {
                                        relationName = f.getName();
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        if (relationName != null) {
                            if (!relationsToFetch.contains(relationName)) relationsToFetch.add(relationName);
                            break;
                        }
                    }
                }
            }

            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(entityClass);
            Root<T> root = query.from(entityClass);

            java.util.Set<String> uniq = new java.util.LinkedHashSet<>(relationsToFetch);
            for (String rel : uniq) {
                try {
                    root.fetch(rel, jakarta.persistence.criteria.JoinType.LEFT);
                } catch (IllegalArgumentException ignored) {}
            }

            query.select(root).where(cb.equal(root.get(entityIdField.getName()), id));

            try {
                return entityManager.createQuery(query).getSingleResult();
            } catch (jakarta.persistence.NoResultException nre) {
                return null;
            }
        } catch (Exception e) {
            try {
                return entityManager.find(entityClass, id);
            } catch (Exception ex) {
                return null;
            }
        }
    }

}
