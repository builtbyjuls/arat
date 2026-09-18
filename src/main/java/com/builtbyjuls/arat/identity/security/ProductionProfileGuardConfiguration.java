package com.builtbyjuls.arat.identity.security;

import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class ProductionProfileGuardConfiguration {

    @Bean
    static BeanFactoryPostProcessor productionProfileGuard() {
        return beanFactory -> {
            var environment = beanFactory.getBean(
                    ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, Environment.class);
            var activeProfiles = Set.of(environment.getActiveProfiles());
            if (activeProfiles.contains("prod")
                    && (activeProfiles.contains("local") || activeProfiles.contains("compose"))) {
                throw new IllegalStateException("The prod profile cannot be combined with local or compose.");
            }
        };
    }
}
