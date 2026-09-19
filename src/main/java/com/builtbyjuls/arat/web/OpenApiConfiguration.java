package com.builtbyjuls.arat.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI aratOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Arat API").version("v1"))
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("Bearer")));
    }

    @Bean
    OpenApiCustomizer planCreationRequestSchema() {
        return openApi -> {
            var request = openApi.getComponents().getSchemas().get("CreatePlanRequest");
            if (request == null) {
                return;
            }
            var categoryAttributes = (Schema<?>) request.getProperties().get("categoryAttributes");
            categoryAttributes.setPropertyNames(new StringSchema().pattern("[a-z][A-Za-z0-9]{0,39}"));
            categoryAttributes.setAdditionalProperties(new Schema<>().oneOf(java.util.List.of(
                    new BooleanSchema(),
                    new IntegerSchema().minimum(new java.math.BigDecimal("0")).maximum(new java.math.BigDecimal("1000000")),
                    new StringSchema().minLength(1).maxLength(120))));
        };
    }
}
