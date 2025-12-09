package com.sharedsync.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

import com.sharedsync.shared.annotation.CacheEntity;

import lombok.Getter;
import lombok.Setter;

@SupportedAnnotationTypes("com.sharedsync.shared.annotation.CacheEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class Generator extends AbstractProcessor {

    List<CacheInformation> cacheInfoList;

    // ⭐ PresenceController가 이미 생성되었는지 추적하는 플래그
    private static boolean presenceControllerGenerated = false;

    public Generator() {
        cacheInfoList = new ArrayList<>();
    }

    // ================================
    // 내부 데이터 구조
    // ================================
    @Getter
    @Setter
    public class FieldInfo {
        private String name;
        private String type;
        private boolean isManyToOne;
        private boolean isOneToMany;
        private boolean isManyToMany;
        private String originalType;
        private String collectionPath;

        public FieldInfo(String name, String type, boolean isManyToOne, boolean isOneToMany, boolean isManyToMany, String collectionPath) {
            this.name = name;
            this.originalType = type;
            this.type = normalizeType(type);
            this.isManyToOne = isManyToOne;
            this.isOneToMany = isOneToMany;
            this.isManyToMany = isManyToMany;
            this.collectionPath = collectionPath;
        }
    }

    @Getter
    @Setter
    public class RelatedEntity {
        private String entityPath;
        private String entityIdType;
        private String entityIdName;
        private String cacheEntityIdName;
        private String repositoryPath;
        private String entityIdOriginalType;
    }

    @Getter
    @Setter
    public class CacheInformation {

        private String entityName;
        private String idType;
        private String idName;
        private String cacheEntityIdName;
        private String idOriginalType;
        private String basicPackagePath;
        private String entityPath;

        private String parentEntityPath;
        private String parentId;

        private String repositoryName;
        private String repositoryPath;
        private List<FieldInfo> entityFields;

        // dto
        private String dtoClassName;
        private String dtoPath;

        // cache
        private String cacheClassName;
        private String cachePath;

        // controller
        private String controllerClassName;
        private String controllerPath;

        // service
        private String serviceClassName;
        private String servicePath;

        // request
        private String requestClassName;
        private String requestPath;

        // response
        private String responseClassName;
        private String responsePath;

        List<RelatedEntity> relatedEntities;

        public CacheInformation() {
            basicPackagePath = "sharedsync";
            relatedEntities = new ArrayList<>();
            entityFields = new ArrayList<>();
        }

        public void addRelatedEntity(RelatedEntity relatedEntity) {
            relatedEntities.add(relatedEntity);
        }

        public void addEntityField(FieldInfo fieldInfo) {
            this.entityFields.add(fieldInfo);
        }
    }

    // ================================
    // Primitive → Wrapper 변환기
    // ================================
    public static String normalizeType(String type) {
        return switch (type) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "boolean" -> "Boolean";
            case "float" -> "Float";
            case "double" -> "Double";
            case "char" -> "Character";
            default -> type;
        };
    }

    public static String denormalizeType(String normalizedType, String originalType) {
        if (originalType != null && isPrimitiveType(originalType)) {
            return originalType;
        }
        return normalizedType;
    }

    private static boolean isPrimitiveType(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "boolean", "float", "double", "char" -> true;
            default -> false;
        };
    }

    // ================================
    // Annotation Processor 메인 처리
    // ================================
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        for (Element element : roundEnv.getElementsAnnotatedWith(CacheEntity.class)) {

            CacheInformation cacheInfo = new CacheInformation();

            String entityName = element.getSimpleName().toString();
            cacheInfo.setEntityName(entityName);
            cacheInfo.setCacheEntityIdName("cache" + entityName + "Id");
            cacheInfo.setEntityPath(element.asType().toString());

            // --- 엔티티 필드 탐색 ---
            for (Element field : element.getEnclosedElements()) {

                if (field.getKind().isField()) {
                    boolean isManyToOne = field.getAnnotation(jakarta.persistence.ManyToOne.class) != null;
                    boolean isOneToMany = field.getAnnotation(jakarta.persistence.OneToMany.class) != null;
                    boolean isManyToMany = field.getAnnotation(jakarta.persistence.ManyToMany.class) != null;

                    String type;
                    String collectionPath = "";

                    if (isOneToMany || isManyToMany) {
                        if (field.asType() instanceof DeclaredType declaredType) {
                            List<? extends javax.lang.model.type.TypeMirror> typeArgs = declaredType.getTypeArguments();
                            collectionPath = declaredType.asElement().toString();
                            type = !typeArgs.isEmpty() ? typeArgs.get(0).toString() : "java.lang.Object";
                        } else {
                            type = "java.lang.Object";
                        }
                    } else {
                        type = field.asType().toString();
                    }

                    cacheInfo.addEntityField(
                            new FieldInfo(field.getSimpleName().toString(), type, isManyToOne, isOneToMany, isManyToMany, collectionPath)
                    );
                }

                // ID 필드 처리
                if (field.getAnnotation(jakarta.persistence.Id.class) != null) {
                    cacheInfo.setIdType(normalizeType(field.asType().toString()));
                    cacheInfo.setIdName(field.getSimpleName().toString());
                    cacheInfo.setIdOriginalType(field.asType().toString());
                }

                // 연관 엔티티 처리
                if (field.getAnnotation(jakarta.persistence.ManyToOne.class) != null ||
                        field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                        field.getAnnotation(jakarta.persistence.ManyToMany.class) != null) {

                    RelatedEntity related = new RelatedEntity();

                    if (field.getAnnotation(jakarta.persistence.ManyToOne.class) != null) {
                        related.setEntityPath(field.asType().toString());
                    }

                    if (field.getAnnotation(jakarta.persistence.OneToMany.class) != null ||
                            field.getAnnotation(jakarta.persistence.ManyToMany.class) != null) {

                        if (field.asType() instanceof DeclaredType dt) {
                            List<? extends javax.lang.model.type.TypeMirror> args = dt.getTypeArguments();
                            related.setEntityPath(!args.isEmpty() ? args.get(0).toString() : "java.lang.Object");
                        }
                    }

                    // 연관 엔티티의 ID 찾기
                    DeclaredType dt = null;
                    if (field.asType() instanceof DeclaredType declaredType) {
                        dt = declaredType;
                    }

                    if (dt != null) {
                        for (Element rf : ((TypeElement) dt.asElement()).getEnclosedElements()) {
                            if (rf.getAnnotation(jakarta.persistence.Id.class) != null) {
                                related.setEntityIdName(rf.getSimpleName().toString());
                                related.setEntityIdType(normalizeType(rf.asType().toString()));
                                related.setEntityIdOriginalType(rf.asType().toString());
                                related.setCacheEntityIdName("cache" + related.getEntityIdName() + "Id");
                                break;
                            }
                        }
                    }

                    cacheInfo.addRelatedEntity(related);

                    // Parent 판정
                    if (field.asType() instanceof DeclaredType dType) {
                        Element typeElement = dType.asElement();
                        if (typeElement.getAnnotation(CacheEntity.class) != null) {
                            cacheInfo.setParentEntityPath(field.asType().toString());
                            for (Element pf : typeElement.getEnclosedElements()) {
                                if (pf.getAnnotation(jakarta.persistence.Id.class) != null) {
                                    cacheInfo.setParentId(pf.getSimpleName().toString());
                                }
                            }
                        }
                    }
                }
            }

            // --- Repository 탐색 ---
            for (Element repoElement : roundEnv.getRootElements()) {
                if (repoElement instanceof TypeElement typeElement) {

                    if (typeElement.getKind().isInterface()) {

                        for (javax.lang.model.type.TypeMirror iface : typeElement.getInterfaces()) {

                            if (iface instanceof DeclaredType dt) {

                                Element ifaceElement = dt.asElement();

                                if (ifaceElement.getSimpleName().toString().equals("JpaRepository")) {

                                    List<? extends javax.lang.model.type.TypeMirror> typeArgs = dt.getTypeArguments();
                                    if (typeArgs.size() == 2) {

                                        String repoEntityType = typeArgs.get(0).toString();

                                        if (repoEntityType.equals(element.asType().toString())) {
                                            cacheInfo.setRepositoryName(removePath(typeElement.getQualifiedName().toString()));
                                            cacheInfo.setRepositoryPath(typeElement.getQualifiedName().toString());
                                        }

                                        // 연관 엔티티 Repository 연결
                                        for (RelatedEntity related : cacheInfo.getRelatedEntities()) {
                                            if (repoEntityType.equals(related.getEntityPath())) {
                                                related.setRepositoryPath(typeElement.getQualifiedName().toString());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            cacheInfoList.add(cacheInfo);

            // 초기화
            initialize(cacheInfo);

            // --- 실제 생성기 호출 ---
            CacheEntityGenerator.process(cacheInfo, processingEnv);
            DtoGenerator.process(cacheInfo, processingEnv);
            WebsocketDtoGenerator.process(cacheInfo, processingEnv);
            ControllerGenerator.process(cacheInfo, processingEnv);
            ServiceGenerator.process(cacheInfo, processingEnv);
            EntityAllArgsConstructorGenerator.process(cacheInfo, processingEnv);

            // ⭐ PresenceController는 단 1회만 생성
            if (!presenceControllerGenerated) {
                PresenceControllerGenerator.generate(processingEnv);
                presenceControllerGenerated = true;
            }
        }

        return false;
    }

    // ================================
    // Generator 초기화
    // ================================
    public static void initialize(CacheInformation cacheInfo) {
        EntityAllArgsConstructorGenerator.initialize(cacheInfo);
        CacheEntityGenerator.initialize(cacheInfo);
        DtoGenerator.initialize(cacheInfo);
        WebsocketDtoGenerator.initialize(cacheInfo);
        ControllerGenerator.initialize(cacheInfo);
        ServiceGenerator.initialize(cacheInfo);
    }

    // ================================
    // 유틸 메서드들
    // ================================
    public static String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public static String decapitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return "";
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    public static String removePath(String fullPath) {
        if (fullPath == null) return null;
        if (fullPath.contains(".")) {
            String[] s = fullPath.split("\\.");
            return s[s.length - 1];
        }
        return fullPath;
    }
}
