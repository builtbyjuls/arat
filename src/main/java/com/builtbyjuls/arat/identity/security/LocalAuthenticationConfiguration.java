package com.builtbyjuls.arat.identity.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
@EnableConfigurationProperties(LocalAuthenticationProperties.class)
class LocalAuthenticationConfiguration {

    @Bean
    LocalTokenAuthenticationProvider localTokenAuthenticationProvider(
            LocalAuthenticationProperties properties) {
        return new LocalTokenAuthenticationProvider(properties);
    }
}
