package com.geminibank.ledger.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Requires a valid bearer JWT on the routes it is mapped to (the /accounts*
 * endpoints). Mirrors the Flask {@code @require_auth} decorator: a missing or
 * invalid token yields 401 {"error":"unauthorized"}. The verified identity is
 * stashed as a request attribute for controllers to read.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_ATTR = "identity";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Identity identity = resolve(request);
        if (identity == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), java.util.Map.of("error", "unauthorized"));
            return false;
        }
        request.setAttribute(IDENTITY_ATTR, identity);
        return true;
    }

    private Identity resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        return jwtService.verify(header.substring(BEARER.length()));
    }
}
