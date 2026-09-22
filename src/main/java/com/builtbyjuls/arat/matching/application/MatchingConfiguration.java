package com.builtbyjuls.arat.matching.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfiguration {
}
