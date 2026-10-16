package com.zhou.peakflow.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 导出 OpenAPI 规范 <b>集成测试</b>（供生成 Postman Collection 用）。
 *
 * <p>要做接口验收与 Postman 集合，逐个接口查路径与入参很麻烦；项目已用 springdoc
 * （{@code knife4j-openapi3}）描述好这些，直接从 {@code /v3/api-docs} 端点拿机器可读定义即可。
 * 走 MockMvc 而不是 curl：当前沙箱对本地端口的 HTTP 访问会被代理拦截（返回 502），
 * MockMvc 不经过网络，更稳定也更适合放进 CI。</p>
 *
 * <p>产出：{@code target/api-docs.json}（完整规范）+ 控制台按 tag 分组的接口清单。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("导出 OpenAPI 规范（接口清单权威依据）")
class OpenApiSpecExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("[工具] GET /v3/api-docs → 导出全部接口定义到 target/api-docs.json 并打印清单")
    void exportOpenApiSpec() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(json != null && !json.isEmpty(), "应能拿到 OpenAPI JSON");
        assertTrue(json.contains("\"paths\""), "响应应是 OpenAPI 文档，实际前 200 字符：" + preview(json));

        // 落盘（用 ASCII 路径，避免中文文件名在部分工具下不稳定）
        Path out = Paths.get("target", "api-docs.json");
        Files.createDirectories(out.getParent());
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(json);
        JsonNode paths = root.path("paths");

        int pathCount = 0;
        int opCount = 0;
        // tag → 接口列表（用 TreeMap 保证输出有序，便于核对）
        Map<String, List<String>> byTag = new TreeMap<>();

        var pathFields = paths.fields();
        while (pathFields.hasNext()) {
            var pf = pathFields.next();
            String path = pf.getKey();
            pathCount++;
            var opFields = pf.getValue().fields();
            while (opFields.hasNext()) {
                var of = opFields.next();
                String method = of.getKey();
                if (!List.of("get", "post", "put", "delete", "patch").contains(method)) {
                    continue;
                }
                opCount++;
                JsonNode op = of.getValue();
                String summary = op.path("summary").asText("");
                boolean authRequired = op.path("security").isArray()
                        && op.path("security").size() > 0;
                List<String> tags = new ArrayList<>();
                op.path("tags").forEach(t -> tags.add(t.asText()));
                if (tags.isEmpty()) {
                    tags.add("(未分组)");
                }
                // 标注权限要求（springdoc 会把 @PreAuthorize 描述进去的注释写在 summary 里，
                // 这里主要靠人工核对；此处只标注是否需要鉴权）
                String line = String.format("%-6s %-42s %s%s", method.toUpperCase(), path,
                        summary.isEmpty() ? "" : summary,
                        authRequired ? "  [需鉴权]" : "");
                for (String t : tags) {
                    byTag.computeIfAbsent(t, k -> new ArrayList<>()).add(line);
                }
            }
        }

        System.out.println();
        System.out.println("════════════════════ OpenAPI 导出结果 ════════════════════");
        System.out.println("  OpenAPI 版本 : " + root.path("openapi").asText());
        System.out.println("  应用标题     : " + root.path("info").path("title").asText());
        System.out.println("  路径数       : " + pathCount);
        System.out.println("  接口数(操作) : " + opCount);
        System.out.println("  输出文件     : " + out.toAbsolutePath());
        System.out.println();
        int idx = 0;
        for (Map.Entry<String, List<String>> e : byTag.entrySet()) {
            System.out.printf("  【%02d】%s  (%d 个接口)%n", ++idx, e.getKey(), e.getValue().size());
            for (String l : e.getValue()) {
                System.out.println("        " + l);
            }
        }
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println();
    }

    private String preview(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
