package com.developerev.security;

import com.developerev.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.springframework.lang.NonNull;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, CustomUserDetailsService customUserDetailsService) {
        this.jwtUtil = jwtUtil;
        this.customUserDetailsService = customUserDetailsService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            final String authorizationHeader = request.getHeader("Authorization");

            String username = null;
            String jwt = null;

            if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                jwt = authorizationHeader.substring(7);
                try {
                    username = jwtUtil.extractUsername(jwt);
                } catch (Exception e) {
                    // Ignore parsing errors and let the authentication fail gracefully
                }
            }

            // Fallback: also accept token as a query parameter (needed for SSE / EventSource
            if (jwt == null) {
                String queryToken = request.getParameter("token");
                if (queryToken != null && !queryToken.isBlank()) {
                    jwt = queryToken;
                    try {
                        username = jwtUtil.extractUsername(jwt);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails userDetails = this.customUserDetailsService.loadUserByUsername(username);

                    if (jwtUtil.validateToken(jwt, userDetails.getUsername())) {
                        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                    }
                } catch (UsernameNotFoundException e) {
                    // If user is not found, just don't set the authentication and let the chain proceed.
                    // This allows registration of new users to work even if an old/invalid token exists.
                    log.debug("User not found during JWT authentication: {}", username);
                }
            }
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error("Authentication filter error: {}", e.getMessage(), e);
            handleException(response, "Authentication Error", "Your session is invalid or has expired.", HttpStatus.UNAUTHORIZED);
        }
    }

    private void handleException(HttpServletResponse response, String error, String message, HttpStatus status) throws IOException {
        ApiErrorResponse errorResponse = ApiErrorResponse.builder()
                .status(status.value())
                .error(error)
                .userMessage(message)
                .debugMessage(status.getReasonPhrase())
                .retryable(false)
                .build();

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
