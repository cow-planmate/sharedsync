package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;
import com.sharedsync.generator.Generator.FieldInfo;
import com.sharedsync.generator.Generator.RelatedEntity;

public class DtoGenerator {
    private static final String OBJECT_NAME = "dto";

    public static void initialize(CacheInformation cacheInfo) {
        String dtoClassName = cacheInfo.getEntityName() + Generator.capitalizeFirst(OBJECT_NAME);
        cacheInfo.setDtoClassName(dtoClassName);
        String packageName = cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME;
        cacheInfo.setDtoPath(packageName);
    }

    public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {

        String source =
            "package " + cacheInfo.getDtoPath() + ";\n"
                + "import com.sharedsync.shared.annotation.*;\n"
                + "import com.sharedsync.shared.dto.CacheDto;\n"
                + writeEntityPath(cacheInfo)
                + "@Cache\n"
                + "@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)\n"
                + "@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)\n"
                + writeAutoDatabaseLoader(cacheInfo)
                + writeAutoEntityConverter(cacheInfo)
                + "public class " + cacheInfo.getDtoClassName()
                + " extends CacheDto<" + cacheInfo.getIdType() + "> {\n\n"
                + writeDtoFields(cacheInfo)
                + writeConstructors(cacheInfo)
                + writeIdGetter(cacheInfo)
                + writeReflectionCacheAndHelpers(cacheInfo)
                + writeFromEntityMethod(cacheInfo)
                + writeToEntityMethodUsingProcessor(cacheInfo)
                + "}";

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(
                    cacheInfo.getDtoPath() + "." + cacheInfo.getDtoClassName());
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    // ==========================================
    // helper: ManyToOne 타입 매칭 정확히 처리
    // ==========================================
    private static boolean isSameEntity(FieldInfo fieldInfo, RelatedEntity related) {
        String fieldType = fieldInfo.getType();         // ex) practice...User 또는 User
        String simple = Generator.removePath(related.getEntityPath()); // User

        return fieldType.endsWith("." + simple)
                || fieldType.equals(simple)
                || fieldType.endsWith("$" + simple);
    }

    // ==========================================
    // Imports
    // ==========================================
    private static String writeEntityPath(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();

        if (cacheInfo.getEntityPath() != null) {
            sb.append("import ").append(cacheInfo.getEntityPath()).append(";\n");
        }
        // Import the per-entity factory class from the AllArgsConstructor package
        sb.append("import sharedsync.allArgsConstructor.").append(cacheInfo.getEntityName()).append("AllArgsConstructor;\n");
        Set<String> collectionImports = new HashSet<>();
        for (RelatedEntity relatedEntity : cacheInfo.getRelatedEntities()) {
            if (relatedEntity.getEntityPath() != null) {
                sb.append("import ").append(relatedEntity.getEntityPath()).append(";\n");
            }
            for(FieldInfo fieldInfo : cacheInfo.getEntityFields()) {
                if ((fieldInfo.isOneToMany() || fieldInfo.isManyToMany()) && isSameEntity(fieldInfo, relatedEntity)) {
                    String collectionType = fieldInfo.getCollectionPath();
                    if (collectionType != null && !collectionType.isEmpty()) {
                        collectionImports.add(collectionType);
                    }
                }
            }
        }
        for (String collectionImport : collectionImports) {
            sb.append("import ").append(collectionImport).append(";\n");
        }
        // If any OneToMany/ManyToMany collections exist, generated DTOs will reference
        // Persistence.getPersistenceUtil().isLoaded(...) to avoid LazyInitializationException.
        boolean hasCollectionRelation = cacheInfo.getEntityFields().stream()
                .anyMatch(f -> f.isOneToMany() || f.isManyToMany());
        if (hasCollectionRelation) {
            sb.append("import jakarta.persistence.Persistence;\n");
        }
        return sb.toString();
    }

    // ==========================================
    // ToEntity (use processor-generated factory)
    // ==========================================
    private static String writeToEntityMethodUsingProcessor(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();
        String idName = cacheInfo.getIdName();

        sb.append("    @EntityConverter\n");
        sb.append("    public ").append(cacheInfo.getEntityName()).append(" toEntity(");

        List<FieldInfo> fields = cacheInfo.getEntityFields();
        boolean first = true;
        for (FieldInfo field : fields) {
            if (field.isManyToOne()) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                sb.append(Generator.removePath(field.getType())).append(" ").append(field.getName());
            }
            if (field.isOneToMany() || field.isManyToMany()) {
                if (!first) {
                    sb.append(", ");
                } else {
                    first = false;
                }
                String colletionType = field.getCollectionPath().split("\\.")[field.getCollectionPath().split("\\.").length - 1];
                sb.append(colletionType).append("<");
                sb.append(Generator.removePath(field.getType())).append("> ").append(field.getName());
            }
        }

        sb.append(") {\n");

        // Use the per-entity factory class (imported above)
        String factoryName = cacheInfo.getEntityName() + "AllArgsConstructor";

        sb.append("        return ").append(factoryName).append(".create(\n");

        for (FieldInfo field : fields) {

            if (field.getName().equals(idName)) {
                sb.append("                ");
                if (field.getName().equals(cacheInfo.getIdName())) {
                    sb.append("this.").append(cacheInfo.getCacheEntityIdName()).append(",\n");
                } else {
                    sb.append("this.").append(field.getName()).append(",\n");
                }
                continue;
            }

            if (field.isManyToOne() || field.isOneToMany() || field.isManyToMany()) {
                // parameter passed into toEntity(...) for relations
                sb.append("                ").append(field.getName()).append(",\n");
            } else {
                sb.append("                this.").append(field.getName()).append(",\n");
            }
        }

        // remove trailing comma and newline
        if (sb.length() >= 2) sb.setLength(sb.length() - 2);
        sb.append("\n        );\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    // ==========================================
    // Reflection cache and helper method (generated when collection relations exist)
    // ==========================================
    private static String writeReflectionCacheAndHelpers(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();

        List<FieldInfo> fields = cacheInfo.getEntityFields();

        // collect fields that need reflection helpers: ManyToOne (single id) and collection relations
        List<FieldInfo> collectionFields = fields.stream()
                .filter(f -> f.isOneToMany() || f.isManyToMany())
                .toList();

        List<FieldInfo> manyToOneFields = fields.stream()
                .filter(f -> f.isManyToOne())
                .filter(f -> cacheInfo.getRelatedEntities().stream().anyMatch(re -> isSameEntity(f, re)))
                .toList();

        if (collectionFields.isEmpty() && manyToOneFields.isEmpty()) return "";

        String entityName = cacheInfo.getEntityName();
        String var = Generator.decapitalizeFirst(entityName);

        // declare cached Field and Method variables per relation field
        // also prepare cache for entity id field
        String idName = cacheInfo.getIdName();
        String idUp = idName.toUpperCase();
        String idType = cacheInfo.getIdType();
        sb.append("    private static final java.lang.reflect.Field FIELD_").append(idUp).append(";\n");

        for (FieldInfo f : collectionFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            sb.append("    private static final java.lang.reflect.Field FIELD_").append(up).append(";\n");
            sb.append("    private static final java.lang.reflect.Method METHOD_GETID_").append(up).append(";\n");
        }
        for (FieldInfo f : manyToOneFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            // avoid duplicate declarations if already declared as collection
            if (collectionFields.stream().noneMatch(cf -> cf.getName().equals(fname))) {
                sb.append("    private static final java.lang.reflect.Field FIELD_").append(up).append(";\n");
                sb.append("    private static final java.lang.reflect.Method METHOD_GETID_").append(up).append(";\n");
            }
        }

        sb.append("\n    static {\n");
        sb.append("        try {\n");
        // initialize id field cache
        sb.append("            FIELD_").append(idUp).append(" = ").append(entityName).append(".class.getDeclaredField(\"").append(idName).append("\");\n");
        sb.append("            FIELD_").append(idUp).append(".setAccessible(true);\n");

        // initialize each cached field and method for collection fields
        for (FieldInfo f : collectionFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(f, re))
                    .findFirst().orElse(null);

            sb.append("            FIELD_").append(up).append(" = ").append(entityName).append(".class.getDeclaredField(\"").append(fname).append("\");\n");
            sb.append("            FIELD_").append(up).append(".setAccessible(true);\n");

            if (matched != null) {
                String elemSimple = Generator.removePath(matched.getEntityPath());
                String idMethod = "get" + Generator.capitalizeFirst(matched.getEntityIdName());
                sb.append("            METHOD_GETID_").append(up).append(" = ").append(elemSimple).append(".class.getMethod(\"").append(idMethod).append("\");\n");
            } else {
                sb.append("            METHOD_GETID_").append(up).append(" = Object.class.getMethod(\"toString\");\n");
            }
        }

        // initialize each cached field and method for many-to-one fields
        for (FieldInfo f : manyToOneFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(f, re))
                    .findFirst().orElse(null);

            sb.append("            FIELD_").append(up).append(" = ").append(entityName).append(".class.getDeclaredField(\"").append(fname).append("\");\n");
            sb.append("            FIELD_").append(up).append(".setAccessible(true);\n");

            if (matched != null) {
                String elemSimple = Generator.removePath(matched.getEntityPath());
                String idMethod = "get" + Generator.capitalizeFirst(matched.getEntityIdName());
                sb.append("            METHOD_GETID_").append(up).append(" = ").append(elemSimple).append(".class.getMethod(\"").append(idMethod).append("\");\n");
            } else {
                sb.append("            METHOD_GETID_").append(up).append(" = Object.class.getMethod(\"toString\");\n");
            }
        }

        sb.append("        } catch (Exception e) {\n");
        sb.append("            throw new ExceptionInInitializerError(e);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // helper to extract entity id (uses cached FIELD_<ID>)
        sb.append("    private static ").append(idType).append(" extractId_").append(idUp)
            .append("(").append(entityName).append(" ").append(var).append(") {\n");
        sb.append("        try {\n");
        sb.append("            Object val = FIELD_").append(idUp).append(".get(").append(var).append(");\n");
        sb.append("            return val == null ? null : (").append(idType).append(") val;\n");
        sb.append("        } catch (IllegalAccessException ex) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // generate typed helper methods per field
        for (FieldInfo f : collectionFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(f, re))
                    .findFirst().orElse(null);
                String elemIdType = matched != null ? Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType()) : "java.lang.Object";

                sb.append("    private static java.util.List<").append(elemIdType).append("> extractIds_").append(up)
                    .append("(").append(entityName).append(" ").append(var).append(") {\n");
            sb.append("        try {\n");
            sb.append("            if (!jakarta.persistence.Persistence.getPersistenceUtil().isLoaded(").append(var).append(", \"").append(fname).append("\")) {\n");
            sb.append("                return java.util.List.of();\n");
            sb.append("            }\n");
            sb.append("            Object val = FIELD_").append(up).append(".get(").append(var).append(");\n");
            sb.append("            if (val == null) return java.util.List.of();\n");
            sb.append("            java.util.Collection<?> coll = (java.util.Collection<?>) val;\n");
            sb.append("            return coll.stream()\n");
            sb.append("                    .filter(java.util.Objects::nonNull)\n");
            sb.append("                    .map(elem -> {\n");
            sb.append("                        try {\n");
            sb.append("                            Object idObj = METHOD_GETID_").append(up).append(".invoke(elem);\n");
            sb.append("                            return (").append(elemIdType).append(") idObj;\n");
            sb.append("                        } catch (Exception ex) {\n");
            sb.append("                            return null;\n");
            sb.append("                        }\n");
            sb.append("                    })\n");
            sb.append("                    .filter(java.util.Objects::nonNull)\n");
            sb.append("                    .toList();\n");
            sb.append("        } catch (IllegalAccessException ex) {\n");
            sb.append("            return java.util.List.of();\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }

        for (FieldInfo f : manyToOneFields) {
            String fname = f.getName();
            String up = fname.toUpperCase();
            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(f, re))
                    .findFirst().orElse(null);
                String elemIdType = matched != null ? Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType()) : "java.lang.Object";

                sb.append("    private static ").append(elemIdType).append(" extractId_").append(up)
                    .append("(").append(entityName).append(" ").append(var).append(") {\n");
            sb.append("        try {\n");
            sb.append("            if (!jakarta.persistence.Persistence.getPersistenceUtil().isLoaded(").append(var).append(", \"").append(fname).append("\")) {\n");
            sb.append("                return null;\n");
            sb.append("            }\n");
            sb.append("            Object rel = FIELD_").append(up).append(".get(").append(var).append(");\n");
            sb.append("            if (rel == null) return null;\n");
            sb.append("            try {\n");
                sb.append("                Object idObj = METHOD_GETID_").append(up).append(".invoke(rel);\n");
                sb.append("                return idObj == null ? null : (").append(elemIdType).append(") idObj;\n");
            sb.append("            } catch (Exception ex) {\n");
            sb.append("                return null;\n");
            sb.append("            }\n");
            sb.append("        } catch (IllegalAccessException ex) {\n");
            sb.append("            return null;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }

        return sb.toString();
    }

    // ==========================================
    // Constructors (generate no-arg + all-args for DTO)
    // ==========================================
    private static String writeConstructors(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();

        // no-arg
        sb.append("    public ").append(cacheInfo.getDtoClassName()).append("() { }\n\n");

        // build parameter list in same order as fromEntity (id first, then entity fields excluding id)
        StringBuilder params = new StringBuilder();
        List<FieldInfo> fields = cacheInfo.getEntityFields();
        // first param: cache id
        params.append(cacheInfo.getIdType()).append(" ").append(cacheInfo.getCacheEntityIdName());

        for (FieldInfo fieldInfo : fields) {
            if (fieldInfo.getName().equals(cacheInfo.getIdName())) continue;

            // ParentId
            if (cacheInfo.getParentEntityPath() != null
                    && isSameEntity(fieldInfo,
                    cacheInfo.getRelatedEntities().stream()
                            .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                            .findFirst()
                            .orElse(null))) {

                RelatedEntity parent = cacheInfo.getRelatedEntities().stream()
                        .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                        .findFirst().orElse(null);

                String parentFieldName = parent.getCacheEntityIdName();
                String parentFieldType = Generator.denormalizeType(parent.getEntityIdType(), parent.getEntityIdOriginalType());

                params.append(", ").append(parentFieldType).append(" ").append(parentFieldName);
                continue;
            }

            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(fieldInfo, re))
                    .findFirst()
                    .orElse(null);

            if (matched != null && fieldInfo.isManyToOne()) {
                String fkFieldName = matched.getCacheEntityIdName();
                String fkFieldType = Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType());
                params.append(", ").append(fkFieldType).append(" ").append(fkFieldName);

            } else if (matched != null && (fieldInfo.isOneToMany() || fieldInfo.isManyToMany())){
                String collectionType = fieldInfo.getCollectionPath().split("\\.")[fieldInfo.getCollectionPath().split("\\.").length -1];
                String fkFieldNames = matched.getCacheEntityIdName()+"s";
                String fkFieldType = collectionType + "<" + Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType()) + ">";
                params.append(", ").append(fkFieldType).append(" ").append(fkFieldNames);

            } else {
                String dtoFieldType = Generator.denormalizeType(fieldInfo.getType(), fieldInfo.getOriginalType());
                params.append(", ").append(dtoFieldType).append(" ").append(fieldInfo.getName());
            }
        }

        // all-args constructor signature
        sb.append("    public ").append(cacheInfo.getDtoClassName()).append("(").append(params.toString()).append(") {\n");

        // assignments
        // first assignment: cache id
        sb.append("        this.").append(cacheInfo.getCacheEntityIdName()).append(" = ").append(cacheInfo.getCacheEntityIdName()).append(";\n");

        for (FieldInfo fieldInfo : fields) {
            if (fieldInfo.getName().equals(cacheInfo.getIdName())) continue;

            if (cacheInfo.getParentEntityPath() != null
                    && isSameEntity(fieldInfo,
                    cacheInfo.getRelatedEntities().stream()
                            .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                            .findFirst()
                            .orElse(null))) {

                RelatedEntity parent = cacheInfo.getRelatedEntities().stream()
                        .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                        .findFirst().orElse(null);
                String parentFieldName = parent.getCacheEntityIdName();
                sb.append("        this.").append(parentFieldName).append(" = ").append(parentFieldName).append(";\n");
                continue;
            }

            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(fieldInfo, re))
                    .findFirst()
                    .orElse(null);

            if (matched != null && fieldInfo.isManyToOne()) {
                String fkFieldName = matched.getCacheEntityIdName();
                sb.append("        this.").append(fkFieldName).append(" = ").append(fkFieldName).append(";\n");
            }
            else if(matched != null && (fieldInfo.isOneToMany() || fieldInfo.isManyToMany())){
                String fkFieldNames = matched.getCacheEntityIdName()+"s";
                sb.append("        this.").append(fkFieldNames).append(" = ").append(fkFieldNames).append(";\n");
            }
            else {
                sb.append("        this.").append(fieldInfo.getName()).append(" = ").append(fieldInfo.getName()).append(";\n");
            }
        }

        sb.append("    }\n\n");

        return sb.toString();
    }

    // ==========================================
    // Getter for cache id
    // ==========================================
    private static String writeIdGetter(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();
        String idType = cacheInfo.getIdType();
        String idField = cacheInfo.getCacheEntityIdName();
        String getterName = "get" + Generator.capitalizeFirst(idField);

        sb.append("    public ").append(idType).append(" ").append(getterName).append("() {\n");
        sb.append("        return this.").append(idField).append(";\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    // ==========================================
    // Auto Annotations
    // ==========================================
    private static String writeAutoDatabaseLoader(CacheInformation cacheInfo) {
        if (cacheInfo.getParentEntityPath() == null || cacheInfo.getParentId() == null) {
            return "";
        }
        return "@AutoDatabaseLoader(repository = \"" + Generator.decapitalizeFirst(cacheInfo.getRepositoryName())
                + "\", method = \"findBy" + Generator.removePath(cacheInfo.getParentEntityPath())
                + Generator.capitalizeFirst(cacheInfo.getParentId()) + "\")\n";
    }

    private static String writeAutoEntityConverter(CacheInformation cacheInfo) {
        if (cacheInfo.getRelatedEntities().isEmpty()) return "";

        StringBuilder repositories = new StringBuilder();
        List<String> repoList = cacheInfo.getRelatedEntities().stream()
                .map(RelatedEntity::getRepositoryPath)
                .toList();

        for (int i = 0; i < repoList.size(); i++) {
            repositories.append("\"")
                    .append(Generator.decapitalizeFirst(Generator.removePath(repoList.get(i))))
                    .append("\"");
            if (i < repoList.size() - 1) repositories.append(", ");
        }

        return "@AutoEntityConverter(repositories = {" + repositories + "})\n";
    }

    // ==========================================
    // DTO Fields
    // ==========================================
    private static String writeDtoFields(CacheInformation cacheInfo) {
        StringBuilder fields = new StringBuilder();

        fields.append("    @CacheId\n");
        fields.append("    private ")
            .append(cacheInfo.getIdType())
                .append(" ")
                .append(cacheInfo.getCacheEntityIdName())
                .append(";\n");

        for (FieldInfo fieldInfo : cacheInfo.getEntityFields()) {

            if (fieldInfo.getName().equals(cacheInfo.getIdName())) continue;

            // ParentId
            if (cacheInfo.getParentEntityPath() != null
                    && isSameEntity(fieldInfo,
                    cacheInfo.getRelatedEntities().stream()
                            .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                            .findFirst()
                            .orElse(null))) {

                RelatedEntity parent = cacheInfo.getRelatedEntities().stream()
                        .filter(re -> re.getEntityPath().equals(cacheInfo.getParentEntityPath()))
                        .findFirst().orElse(null);

                String parentFieldName = parent.getCacheEntityIdName();
                String parentFieldType = Generator.denormalizeType(parent.getEntityIdType(), parent.getEntityIdOriginalType());

                fields.append("    @ParentId(")
                        .append(Generator.removePath(parent.getEntityPath()))
                        .append(".class)\n");

                fields.append("    private ")
                    .append(parentFieldType)
                        .append(" ")
                        .append(parentFieldName)
                        .append(";\n");

                continue;
            }

            // ManyToOne
            RelatedEntity matched = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(fieldInfo, re))
                    .findFirst()
                    .orElse(null);

            if (matched != null && fieldInfo.isManyToOne()) {
                String fkFieldName = matched.getCacheEntityIdName();
                String fkFieldType = Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType());

                fields.append("    private ")
                    .append(fkFieldType)
                        .append(" ")
                        .append(fkFieldName)
                        .append(";\n");

            }
            else if(matched != null && (fieldInfo.isOneToMany() || fieldInfo.isManyToMany())){
                String collectionType = fieldInfo.getCollectionPath().split("\\.")[fieldInfo.getCollectionPath().split("\\.").length -1];
                String fkFieldNames = matched.getCacheEntityIdName()+"s";
                String fkFieldType = collectionType + "<" + Generator.denormalizeType(matched.getEntityIdType(), matched.getEntityIdOriginalType()) + ">";

                fields.append("    private ")
                    .append(fkFieldType)
                        .append(" ")
                        .append(fkFieldNames)
                        .append(";\n");

            }
            else {
                String dtoFieldType = Generator.denormalizeType(fieldInfo.getType(), fieldInfo.getOriginalType());
                fields.append("    private ")
                    .append(dtoFieldType)
                        .append(" ")
                        .append(fieldInfo.getName())
                        .append(";\n");
            }
        }

        fields.append("\n");
        return fields.toString();
    }

    // ==========================================
    // FromEntity
    // ==========================================
    private static String writeFromEntityMethod(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();
        String var = Generator.decapitalizeFirst(cacheInfo.getEntityName());
        String idName = cacheInfo.getIdName();

        sb.append("    public static ").append(cacheInfo.getDtoClassName())
            .append(" fromEntity(")
            .append(cacheInfo.getEntityName())
            .append(" ")
            .append(var)
            .append(") {\n");

        sb.append("        return new ").append(cacheInfo.getDtoClassName()).append("(\n");
        String idUp = idName.toUpperCase();
        sb.append("                ");
        sb.append("extractId_").append(idUp).append("(").append(var).append("),\n");

        for (FieldInfo field : cacheInfo.getEntityFields()) {

            if (field.getName().equals(idName)) continue;

            RelatedEntity match = cacheInfo.getRelatedEntities().stream()
                    .filter(re -> isSameEntity(field, re))
                    .findFirst().orElse(null);

            if (match != null) {
                // ManyToOne -> use reflection helper to extract related id (handles missing getters)
                if (field.isManyToOne()) {
                    String fname = field.getName();
                    String up = fname.toUpperCase();
                    sb.append("                ");
                    sb.append("extractId_").append(up).append("(").append(var).append("),\n");
                    continue;
                }

                // OneToMany/ManyToMany -> use typed reflection helper to extract list of ids
                if (field.isOneToMany() || field.isManyToMany()) {
                    String fname = field.getName();
                    String up = fname.toUpperCase();
                    sb.append("                ");
                    sb.append("extractIds_").append(up).append("(").append(var).append("),\n");
                    continue;
                }
            }

                boolean isBoolean =
                    "boolean".equals(field.getOriginalType()) || "Boolean".equals(field.getType()) || "java.lang.Boolean".equals(field.getType());

            boolean nameStartsWithIs = field.getName().startsWith("is")
                    && field.getName().length() > 2
                    && Character.isUpperCase(field.getName().charAt(2));

            String prefix;
            if (nameStartsWithIs) {
                // field already starts with 'is' (e.g. isDeleted) -> use getIsDeleted() to match Lombok/JavaBean for such fields
                prefix = "get";
            } else {
                prefix = isBoolean ? "is" : "get";
            }

            sb.append("                ")
                    .append(var).append(".")
                    .append(prefix).append(Generator.capitalizeFirst(field.getName()))
                    .append("(),\n");
        }

        sb.setLength(sb.length() - 2);
        sb.append("\n        );\n");
        sb.append("    }\n\n");

        return sb.toString();
    }

    // ==========================================
    // ToEntity
    // ==========================================
    private static String writeToEntityMethod(CacheInformation cacheInfo) {
        StringBuilder sb = new StringBuilder();
        String idName = cacheInfo.getIdName();

        sb.append("    @EntityConverter\n");
        sb.append("    public ").append(cacheInfo.getEntityName()).append(" toEntity(");

        List<FieldInfo> fields = cacheInfo.getEntityFields();
        boolean first = true;
        for (FieldInfo field : fields) {
            
            if (field.isManyToOne()) {
                if (!first) {
                sb.append(", ");
                } else {
                    first = false;
                }
                sb.append(Generator.removePath(field.getType())).append(" ").append(field.getName());
            }
            if(field.isOneToMany() || field.isManyToMany()){
                if (!first) {
                sb.append(", ");
                } else {
                    first = false;
                }
                String colletionType = field.getCollectionPath().split("\\.")[field.getCollectionPath().split("\\.").length -1];
                sb.append(colletionType).append("<");
                sb.append(Generator.removePath(field.getType())).append(">").append(" ").append(field.getName());
            }
        }

        sb.append(") {\n");
        sb.append("        return new ").append(cacheInfo.getEntityName()).append("(\n");

        for (FieldInfo field : fields) {

            if (field.getName().equals(idName)) {
                sb.append("                ");
                if(field.getName().equals(cacheInfo.getIdName())){
                    sb.append("this.").append(cacheInfo.getCacheEntityIdName()).append(",\n");
                }
                else{
                    sb.append("this.").append(field.getName()).append(",\n");
                }
                continue;
            }

            if (field.isManyToOne() || field.isOneToMany() || field.isManyToMany()) {
                // parameter passed into toEntity(...) for relations
                sb.append("                ").append(field.getName()).append(",\n");
            } else {
                sb.append("                this.").append(field.getName()).append(",\n");
            }
        }

        // remove trailing comma and newline
        if (sb.length() >= 2) sb.setLength(sb.length() - 2);
        sb.append("\n        );\n");
        sb.append("    }\n");

        return sb.toString();
    }
}
