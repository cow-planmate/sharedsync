package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

public class PresenceControllerGenerator {

    private static final String PACKAGE_NAME = "sharedsync.presence";
    private static final String CLASS_NAME = "PresenceController";

    public static void generate(ProcessingEnvironment processingEnv) {

        try {
            String fullPath = PACKAGE_NAME + "." + CLASS_NAME;
            JavaFileObject file = processingEnv.getFiler().createSourceFile(fullPath);

            try (Writer writer = file.openWriter()) {

                writer.write("""
                    package %s;

                    import com.sharedsync.shared.presence.core.SharedPresenceFacade;
                    import com.sharedsync.shared.presence.dto.PresenceSnapshot;
                    import lombok.RequiredArgsConstructor;
                    import org.springframework.web.bind.annotation.GetMapping;
                    import org.springframework.web.bind.annotation.PathVariable;
                    import org.springframework.web.bind.annotation.RequestMapping;
                    import org.springframework.web.bind.annotation.RestController;

                    import java.util.List;

                    @RestController
                    @RequestMapping("/presence")
                    @RequiredArgsConstructor
                    public class %s {

                        private final SharedPresenceFacade presenceFacade;

                        @GetMapping("/{roomId}")
                        public List<PresenceSnapshot> getPresence(@PathVariable("roomId") String roomId) {
                            return presenceFacade.getPresence(roomId);
                        }
                    }
                """.formatted(PACKAGE_NAME, CLASS_NAME));
            }

            processingEnv.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.NOTE,
                    "[SharedSync] PresenceController generated at: " + PACKAGE_NAME + "." + CLASS_NAME
            );

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    javax.tools.Diagnostic.Kind.ERROR,
                    "[SharedSync] Failed to generate PresenceController: " + e.getMessage()
            );
        }
    }
}
