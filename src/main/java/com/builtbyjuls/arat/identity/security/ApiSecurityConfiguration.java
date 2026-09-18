package com.builtbyjuls.arat.identity.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class ApiSecurityConfiguration {

    @Bean
    AuthenticationManager apiAuthenticationManager(
            ObjectProvider<LocalTokenAuthenticationProvider> localTokenAuthenticationProvider) {
        var provider = localTokenAuthenticationProvider.getIfAvailable();
        if (provider != null) {
            return new ProviderManager(provider);
        }
        return authentication -> {
            throw new BadCredentialsException("Authentication is unavailable.");
        };
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            AuthenticationManager apiAuthenticationManager,
            ObjectProvider<LocalTokenAuthenticationProvider> localTokenAuthenticationProvider) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        if (localTokenAuthenticationProvider.getIfAvailable() != null) {
            http.addFilterBefore(
                    new LocalBearerAuthenticationFilter(apiAuthenticationManager, authenticationEntryPoint),
                    AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }
}
