package com.magicvvu.fanzha.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("反诈通后端 API")
                        .description("反诈通账户、AI 对话、资料入库与反诈资讯 ETL 接口")
                        .version("v1"));
    }
}
