package com.sharedsync.shared.repository.helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class RepositoryReflectionUtils {

    public static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
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

    public static Field locateEntityIdField(Class<?> entityClass) {
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

    public static List<Field> getAllFieldsInHierarchy(Class<?> clazz) {
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

    public static String getTableName(Class<?> entityClass) {
        jakarta.persistence.Table table = entityClass.getAnnotation(jakarta.persistence.Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return entityClass.getSimpleName();
    }

    public static String getColumnName(Field field) {
        jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
        if (column != null && !column.name().isEmpty()) {
            return column.name();
        }
        // @Id 필드 처리
        if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
            jakarta.persistence.Column idColumn = field.getAnnotation(jakarta.persistence.Column.class);
            if (idColumn != null && !idColumn.name().isEmpty()) {
                return idColumn.name();
            }
        }
        return camelToSnake(field.getName());
    }

    public static String camelToSnake(String camelCase) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    public static String deriveSequenceName(Class<?> entityClass, Field idField) {
        String tableName = getTableName(entityClass);
        String columnName = getColumnName(idField);
        return tableName + "_" + columnName + "_seq";
    }

    public static boolean isNumericIdType(Class<?> type) {
        return Number.class.isAssignableFrom(type)
                || type == long.class || type == int.class
                || type == short.class || type == byte.class;
    }

    public static Field findFieldWithAnnotation(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotationClass)) {
                return field;
            }
        }
        return null;
    }

    public static Method findMethodWithAnnotation(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotationClass) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(annotationClass)) {
                return method;
            }
        }
        return null;
    }

    public static Object convertIdToType(Class<?> targetType, Object idValue) {
        if (idValue == null) return null;
        if (targetType == null) return idValue;

        if (targetType.isInstance(idValue)) return idValue;

        String s = idValue.toString();
        try {
            if (targetType == String.class) return s;
            if (targetType == Integer.class || targetType == int.class) return Integer.valueOf(s);
            if (targetType == Long.class || targetType == long.class) return Long.valueOf(s);
            if (targetType == Short.class || targetType == short.class) return Short.valueOf(s);
            if (targetType == Byte.class || targetType == byte.class) return Byte.valueOf(s);
            if (targetType == java.util.UUID.class) return java.util.UUID.fromString(s);
        } catch (Exception e) {}
        return idValue;
    }
}
