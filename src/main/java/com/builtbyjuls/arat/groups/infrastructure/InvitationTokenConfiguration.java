package com.builtbyjuls.arat.groups.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvitationTokenProperties.class)
class InvitationTokenConfiguration {
}
