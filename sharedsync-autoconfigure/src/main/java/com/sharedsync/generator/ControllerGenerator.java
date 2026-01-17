package com.sharedsync.generator;

import java.io.IOException;
import java.io.Writer;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;

import com.sharedsync.generator.Generator.CacheInformation;

public class ControllerGenerator {
	private static final String OBJECT_NAME = "controller";

	public static void initialize(CacheInformation cacheInfo) {
		cacheInfo.setControllerClassName("Shared" + cacheInfo.getEntityName() + Generator.capitalizeFirst(OBJECT_NAME));
		cacheInfo.setControllerPath(cacheInfo.getBasicPackagePath() + "." + OBJECT_NAME);
	}

	public static boolean process(CacheInformation cacheInfo, ProcessingEnvironment processingEnv) {
		return true;
	}

	public static void generateUnified(java.util.List<CacheInformation> cacheInfoList,
			ProcessingEnvironment processingEnv) {
		if (cacheInfoList.isEmpty())
			return;

		StringBuilder source = new StringBuilder();
		source.append("package sharedsync.controller;\n\n");

		source.append("import org.springframework.messaging.handler.annotation.MessageMapping;\n");
		source.append("import org.springframework.messaging.handler.annotation.SendTo;\n");
		source.append("import org.springframework.messaging.handler.annotation.DestinationVariable;\n");
		source.append("import org.springframework.messaging.handler.annotation.Payload;\n");
		source.append("import org.springframework.stereotype.Controller;\n");
		source.append("import com.fasterxml.jackson.databind.ObjectMapper;\n");
		source.append("import com.sharedsync.shared.dto.WResponse;\n");
		source.append("import com.sharedsync.shared.dto.WRequest;\n");
		source.append("import com.sharedsync.shared.sync.RedisSyncService;\n\n");

		for (CacheInformation info : cacheInfoList) {
			source.append("import ").append(info.getRequestPath()).append(".").append(info.getRequestClassName())
					.append(";\n");
			source.append("import ").append(info.getResponsePath()).append(".").append(info.getResponseClassName())
					.append(";\n");
			source.append("import ").append(info.getServicePath()).append(".").append(info.getServiceClassName())
					.append(";\n");
		}
		source.append("\n");

		source.append("@Controller\n");
		source.append("public class SharedSyncController {\n\n");

		source.append("    private final ObjectMapper objectMapper;\n");
		source.append("    private final RedisSyncService redisSyncService;\n");
		for (CacheInformation info : cacheInfoList) {
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("    private final ").append(info.getServiceClassName()).append(" ").append(serviceVar)
					.append(";\n");
		}
		source.append("\n");

		source.append("    public SharedSyncController(ObjectMapper objectMapper, RedisSyncService redisSyncService");
		for (CacheInformation info : cacheInfoList) {
			source.append(", ").append(info.getServiceClassName()).append(" ").append(decapitalizeFirst(info.getServiceClassName()));
		}
		source.append(") {\n");
		source.append("        this.objectMapper = objectMapper;\n");
		source.append("        this.redisSyncService = redisSyncService;\n");
		for (CacheInformation info : cacheInfoList) {
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("        this.").append(serviceVar).append(" = ").append(serviceVar).append(";\n");
		}
		source.append("    }\n\n");

		source.append("    @MessageMapping(\"/{roomId}\")\n");
		source.append("    public void handle(@DestinationVariable(\"roomId\") int roomId, \n");
		source.append("                         @Payload java.util.Map<String, Object> payload) {\n\n");
		source.append("        String entity = (String) payload.get(\"entity\");\n");
		source.append("        if (entity == null) return;\n\n");

		source.append("        Object result = null;\n");
		source.append("        switch (entity.toLowerCase()) {\n");
		for (CacheInformation info : cacheInfoList) {
			String entityLower = info.getEntityName().toLowerCase();
			String serviceVar = decapitalizeFirst(info.getServiceClassName());
			source.append("            case \"").append(entityLower).append("\": {\n");
			source.append("                ").append(info.getRequestClassName()).append(" request = objectMapper.convertValue(payload, ").append(info.getRequestClassName()).append(".class);\n");
			source.append("                result = handleAction(").append(serviceVar).append(", request);\n");
			source.append("                break;\n");
			source.append("            }\n");
		}
		source.append("            default:\n");
		source.append("                break;\n");
		source.append("        }\n");
		source.append("        if (result != null) {\n");
		source.append("            redisSyncService.publish(\"/topic/\" + roomId, result);\n");
		source.append("        }\n");
		source.append("    }\n\n");

		source.append("    private <Req extends WRequest, Res extends WResponse> Res handleAction(com.sharedsync.shared.service.SharedService<Req, Res> service, Req request) {\n");
		source.append("        String action = request.getAction();\n");
		source.append("        if (action == null) return null;\n\n");
		source.append("        Res response = switch (action.toLowerCase()) {\n");
		source.append("            case \"create\" -> service.create(request);\n");
		source.append("            case \"read\" -> service.read(request);\n");
		source.append("            case \"update\" -> service.update(request);\n");
		source.append("            case \"delete\" -> service.delete(request);\n");
		source.append("            default -> null;\n");
		source.append("        };\n");
		source.append("        if (response != null) {\n");
		source.append("            response.setAction(action);\n");
		source.append("            response.setEntity(request.getEntity());\n");
		source.append("            response.setEventId(request.getEventId() == null ? \"\" : request.getEventId());\n");
		source.append("        }\n");
		source.append("        return response;\n");
		source.append("    }\n");

		source.append("}\n");

		// 파일 생성
		try {
			JavaFileObject file = processingEnv.getFiler().createSourceFile("sharedsync.controller.SharedSyncController");
			Writer writer = file.openWriter();
			writer.write(source.toString());
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static String decapitalizeFirst(String str) {
		if (str == null || str.isEmpty())
			return "";
		return str.substring(0, 1).toLowerCase() + str.substring(1);
	}

	private static String capitalizeFirst(String str) {
		if (str == null || str.isEmpty())
			return "";
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}
}
