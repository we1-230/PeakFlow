package com.zhou.peakflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger 文档配置（全局唯一入口）
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名，在 Swagger UI 的 Authorize 按钮与全局 SecurityRequirement 中引用 */
    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        // 1. 文档基本信息（原 SwaggerConfig 的内容）
        Info info = new Info()
                .title("PeakFlow 商城API文档")
                .version("1.0.0")
                .description("高并发秒杀商城系统接口文档")
                .contact(new Contact()
                        .name("zhou")
                        .email("your-email@example.com"));

        // 2. 安全方案：Authorization 请求头，HTTP Bearer 格式
        SecurityScheme securityScheme = new SecurityScheme()
                .name("Authorization")          // 请求头名字
                .type(SecurityScheme.Type.HTTP) // 类型：HTTP
                .scheme("bearer")               // 方案：bearer
                .bearerFormat("JWT");           // 格式提示，UI 上会显示

        // 3. 全局安全要求：所有接口默认都需要该方案
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList(SECURITY_SCHEME_NAME);

        // 4. 组装 OpenAPI 对象
        return new OpenAPI()
                .info(info)
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME, securityScheme))
                .addSecurityItem(securityRequirement);
    }
}
