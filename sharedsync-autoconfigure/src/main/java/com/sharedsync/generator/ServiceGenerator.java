package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

public class ServiceGenerator {
	private static final String OBJECT_NAME = "service";

	public static void initialize(CacheInformation cacheInfo) {
		cacheInfo.setServiceClassName("Shared" + cacheInfo.getEntityName() + Generator.capitalizeFirst(OBJECT_NAME));
		cacheInfo.setServicePath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
	}

	public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {

		String idType = cacheInfo.getIdType();      // Long / Integer
		String idGetter = "get" + Generator.capitalizeFirst(cacheInfo.getCacheEntityIdName()) + "()";

		StringBuilder source = new StringBuilder();
		source.append("package ").append(cacheInfo.getServicePath()).append(";\n\n");
		source.append("import org.springframework.stereotype.Service;\n");
		source.append("import lombok.RequiredArgsConstructor;\n\n");
		source.append("import ").append(cacheInfo.getEntityPath()).append(";\n");
		source.append("import ").append(cacheInfo.getRequestPath()).append(".").append(cacheInfo.getRequestClassName()).append(";\n");
		source.append("import ").append(cacheInfo.getResponsePath()).append(".").append(cacheInfo.getResponseClassName()).append(";\n");
		source.append("import ").append(cacheInfo.getCachePath()).append(".").append(cacheInfo.getCacheClassName()).append(";\n");
		source.append("import ").append(cacheInfo.getDtoPath()).append(".").append(cacheInfo.getDtoClassName()).append(";\n");
		source.append("import com.sharedsync.shared.service.SharedService;\n\n");

		source.append("@Service\n");
		source.append("@RequiredArgsConstructor\n");
		source.append("public class ").append(cacheInfo.getServiceClassName())
				.append(" implements SharedService<")
				.append(cacheInfo.getRequestClassName()).append(", ")
				.append(cacheInfo.getResponseClassName()).append("> {\n\n");

		source.append("    private final ").append(cacheInfo.getCacheClassName())
				.append(" ").append(decapitalize(cacheInfo.getCacheClassName())).append(";\n\n");

		String cacheBean = decapitalize(cacheInfo.getCacheClassName());

		// =====================================
		// CREATE
		// =====================================
		source.append("    public ").append(cacheInfo.getResponseClassName()).append(" create(")
				.append(cacheInfo.getRequestClassName()).append(" request) {\n");
		source.append("        ").append(cacheInfo.getResponseClassName()).append(" response = new ")
				.append(cacheInfo.getResponseClassName()).append("();\n");
		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> payload = request.get")
				.append(cacheInfo.getDtoClassName()).append("s() != null ? request.get")
				.append(cacheInfo.getDtoClassName()).append("s() : java.util.Collections.emptyList();\n");
		source.append("        if (payload.isEmpty()) {\n");
		source.append("            response.set").append(cacheInfo.getDtoClassName()).append("s(java.util.Collections.emptyList());\n");
		source.append("            return response;\n");
		source.append("        }\n");
		source.append("        java.util.List<").append(cacheInfo.getDtoClassName())
				.append("> sanitized = payload.stream()\n");
		if (cacheInfo.getIdType().equals("String")) {
			source.append("            .map(dto -> dto.<").append(cacheInfo.getDtoClassName())
					.append(">changeId(java.util.UUID.randomUUID().toString()))\n");
		} else {
			source.append("            .map(dto -> dto.<").append(cacheInfo.getDtoClassName()).append(">changeId(null))\n");
		}

		source.append("            .collect(java.util.stream.Collectors.toList());\n");
		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> saved = ")
				.append(cacheBean).append(".saveAll(sanitized);\n");
		source.append("        response.set").append(cacheInfo.getDtoClassName()).append("s(saved);\n");
		source.append("        return response;\n");
		source.append("    }\n\n");

		// =====================================
		// READ
		// =====================================
		source.append("    public ").append(cacheInfo.getResponseClassName()).append(" read(")
				.append(cacheInfo.getRequestClassName()).append(" request) {\n");
		source.append("        ").append(cacheInfo.getResponseClassName()).append(" response = new ")
				.append(cacheInfo.getResponseClassName()).append("();\n");
		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> payload = request.get")
				.append(cacheInfo.getDtoClassName()).append("s() != null ? request.get")
				.append(cacheInfo.getDtoClassName()).append("s() : java.util.Collections.emptyList();\n");
		source.append("        if (payload.isEmpty()) {\n");
		source.append("            response.set").append(cacheInfo.getDtoClassName()).append("s(java.util.Collections.emptyList());\n");
		source.append("            return response;\n");
		source.append("        }\n");

		// ID 타입 자동 Long/Integer 처리
		source.append("        java.util.List<").append(idType).append("> ids = payload.stream()\n");
		source.append("            .map(dto -> dto.").append(idGetter).append(")\n");
		source.append("            .filter(java.util.Objects::nonNull)\n");
		source.append("            .collect(java.util.stream.Collectors.toList());\n");

		source.append("        if (ids.isEmpty()) {\n");
		source.append("            response.set").append(cacheInfo.getDtoClassName()).append("s(java.util.Collections.emptyList());\n");
		source.append("            return response;\n");
		source.append("        }\n");

		source.append("        java.util.List<").append(cacheInfo.getEntityName()).append("> entities = ")
				.append(cacheBean).append(".findAllById(ids);\n");

		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> dtos = entities.stream()\n");
		source.append("            .filter(java.util.Objects::nonNull)\n");
		source.append("            .map(").append(cacheInfo.getDtoClassName()).append("::fromEntity)\n");
		source.append("            .collect(java.util.stream.Collectors.toList());\n");

		source.append("        response.set").append(cacheInfo.getDtoClassName()).append("s(dtos);\n");
		source.append("        return response;\n");
		source.append("    }\n\n");

		// =====================================
		// UPDATE
		// =====================================
		source.append("    public ").append(cacheInfo.getResponseClassName()).append(" update(")
				.append(cacheInfo.getRequestClassName()).append(" request) {\n");
		source.append("        ").append(cacheInfo.getResponseClassName()).append(" response = new ")
				.append(cacheInfo.getResponseClassName()).append("();\n");

		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> payload = request.get")
				.append(cacheInfo.getDtoClassName()).append("s() != null ? request.get")
				.append(cacheInfo.getDtoClassName()).append("s() : java.util.Collections.emptyList();\n");
		source.append("        if (payload.isEmpty()) {\n");
		source.append("            response.set").append(cacheInfo.getDtoClassName()).append("s(java.util.Collections.emptyList());\n");
		source.append("            return response;\n");
		source.append("        }\n");

		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> updated = payload.stream()\n");
		source.append("            .map(").append(cacheBean).append("::update)\n");
		source.append("            .collect(java.util.stream.Collectors.toList());\n");

		source.append("        response.set").append(cacheInfo.getDtoClassName()).append("s(updated);\n");
		source.append("        return response;\n");
		source.append("    }\n\n");

		// =====================================
		// DELETE
		// =====================================
		source.append("    public ").append(cacheInfo.getResponseClassName()).append(" delete(")
				.append(cacheInfo.getRequestClassName()).append(" request) {\n");
		source.append("        ").append(cacheInfo.getResponseClassName()).append(" response = new ")
				.append(cacheInfo.getResponseClassName()).append("();\n");

		source.append("        java.util.List<").append(cacheInfo.getDtoClassName()).append("> payload = request.get")
				.append(cacheInfo.getDtoClassName()).append("s() != null ? request.get")
				.append(cacheInfo.getDtoClassName()).append("s() : java.util.Collections.emptyList();\n");
		source.append("        if (payload.isEmpty()) {\n");
		source.append("            response.set").append(cacheInfo.getDtoClassName()).append("s(java.util.Collections.emptyList());\n");
		source.append("            return response;\n");
		source.append("        }\n");

		source.append("        java.util.List<").append(idType).append("> ids = payload.stream()\n");
		source.append("            .map(dto -> dto.").append(idGetter).append(")\n");
		source.append("            .filter(java.util.Objects::nonNull)\n");
		source.append("            .collect(java.util.stream.Collectors.toList());\n");

		source.append("        if (!ids.isEmpty()) {\n");
		source.append("            ").append(cacheBean).append(".deleteAllById(ids);\n");
		source.append("        }\n");

		source.append("        response.set").append(cacheInfo.getDtoClassName()).append("s(payload);\n");
		source.append("        return response;\n");
		source.append("    }\n\n");

		source.append("}\n");

		try {
			JavaFileObject file = processingEnv.getFiler()
					.createSourceFile(cacheInfo.getServicePath() + "." + cacheInfo.getServiceClassName());
			Writer writer = file.openWriter();
			writer.write(source.toString());
			writer.close();
		} catch (IOException e) {
			throw new RuntimeException("Failed to generate service: " + cacheInfo.getServiceClassName(), e);
		}

		return true;
	}

	private static String decapitalize(String str) {
		return Character.toLowerCase(str.charAt(0)) + str.substring(1);
	}
}
