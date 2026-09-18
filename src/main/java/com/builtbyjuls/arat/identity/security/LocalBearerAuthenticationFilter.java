package com.builtbyjuls.arat.identity.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

final class LocalBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    LocalBearerAuthenticationFilter(
            AuthenticationManager authenticationManager, AuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationManager = authenticationManager;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !hasBearerScheme(authorization)) {
            filterChain.doFilter(request, response);
            return;
        }

        var tokenStart = BEARER_PREFIX.length() - 1;
        while (tokenStart < authorization.length() && Character.isWhitespace(authorization.charAt(tokenStart))) {
            tokenStart++;
        }
        var bearerToken = authorization.substring(tokenStart);
        if (bearerToken.isBlank() || bearerToken.chars().anyMatch(Character::isWhitespace)) {
            reject(request, response);
            return;
        }

        try {
            var authentication = authenticationManager.authenticate(new LocalBearerTokenAuthentication(bearerToken));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (AuthenticationException exception) {
            reject(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        authenticationEntryPoint.commence(request, response, new BadCredentialsException("Invalid bearer token."));
    }

    private boolean hasBearerScheme(String authorization) {
        return authorization.length() >= BEARER_PREFIX.length()
                && authorization.regionMatches(true, 0, "Bearer", 0, BEARER_PREFIX.length() - 1)
                && Character.isWhitespace(authorization.charAt(BEARER_PREFIX.length() - 1));
    }
}
