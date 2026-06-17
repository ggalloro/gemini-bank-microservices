package com.geminibank.ledger;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.geminibank.ledger.auth.AuthInterceptor;

/**
 * Applies the JWT auth interceptor to the account endpoints only, matching the
 * Flask service: /accounts and its sub-paths require auth, while
 * /internal/transactions and /healthz are open (reachable on the service network).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/accounts", "/accounts/**");
    }
}
