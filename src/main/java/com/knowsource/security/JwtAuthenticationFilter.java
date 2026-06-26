package com.knowsource.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final CurrentUserService currentUserService;

    public JwtAuthenticationFilter(JwtService jwtService, CurrentUserService currentUserService) {
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            try {
                CurrentUser tokenUser = jwtService.parseAccessToken(authorization.substring("Bearer ".length()));
                CurrentUser currentUser = currentUserService.findByUsername(tokenUser.username());
                if (currentUser.id() != tokenUser.id()
                        || currentUser.tokenVersion() != tokenUser.tokenVersion()
                        || !currentUser.globalRole().equals(tokenUser.globalRole())) {
                    throw new IllegalArgumentException("Stale access token.");
                }
                CurrentUserPrincipal principal = new CurrentUserPrincipal(currentUser);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
            } catch (IllegalArgumentException | AuthenticationException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
