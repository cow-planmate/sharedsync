package com.sharedsync.shared.processor;

import com.sharedsync.shared.annotation.CacheEntity;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes("com.sharedsync.shared.annotation.CacheEntity")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class CacheEntityProcessor extends AbstractProcessor {

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(CacheEntity.class)) {
            if (annotatedElement.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@CacheEntity can only be applied to classes");
                continue;
            }

            TypeElement typeElement = (TypeElement) annotatedElement;
            try {
                generateAllArgsFactory(typeElement);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate factory: " + e.getMessage());
            }
        }
        return true; // claim the annotation
    }

    private void generateAllArgsFactory(TypeElement type) throws IOException {
        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        String className = type.getSimpleName().toString();
        String factoryClassName = className + "AllArgsConstructor";
        String qualifiedFactory = pkg.isEmpty() ? factoryClassName : pkg + "." + factoryClassName;

        List<VariableElement> fields = new ArrayList<>();
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() == ElementKind.FIELD) {
                VariableElement ve = (VariableElement) e;
                // ignore static fields
                Set<javax.lang.model.element.Modifier> mods = ve.getModifiers();
                if (mods.contains(javax.lang.model.element.Modifier.STATIC)) continue;
                fields.add(ve);
            }
        }

        JavaFileObject jfo = processingEnv.getFiler().createSourceFile(qualifiedFactory, type);
        try (Writer w = jfo.openWriter()) {
            if (!pkg.isEmpty()) {
                w.write("package " + pkg + ";\n\n");
            }

            w.write("public class " + factoryClassName + " {\n\n");

            // generate create method signature
            w.write("    public static " + className + " create(");
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                VariableElement f = fields.get(i);
                String t = f.asType().toString();
                String n = f.getSimpleName().toString();
                params.append(t).append(" ").append(n);
                if (i < fields.size() - 1) params.append(", ");
            }
            w.write(params.toString());
            w.write(") {\n");

            // instantiate
            w.write("        " + className + " instance = new " + className + "();\n");

            // set fields via reflection to avoid requiring setters
            w.write("        try {\n");
            for (VariableElement f : fields) {
                String t = f.asType().toString();
                String n = f.getSimpleName().toString();
                w.write("            java.lang.reflect.Field f_" + n + " = " + className + ".class.getDeclaredField(\"" + n + "\");\n");
                w.write("            f_" + n + ".setAccessible(true);\n");
                w.write("            f_" + n + ".set(instance, " + n + ");\n");
            }
            w.write("        } catch (Exception e) {\n");
            w.write("            throw new RuntimeException(e);\n");
            w.write("        }\n");

            w.write("        return instance;\n");
            w.write("    }\n\n");

            w.write("}\n");
        }
    }
}
