package com.ecommerce.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * Rate-limits per authenticated user (falls back to "anonymous" for public routes)
     * rather than per IP — fair for users behind a shared corporate NAT, and it's the
     * identity that actually maps to a customer's fair-use quota.
     *
     * {@code @Primary} because Spring Cloud Gateway's RequestRateLimiterGatewayFilterFactory
     * also autowires a single default KeyResolver bean (used when a route doesn't name one
     * via key-resolver SpEL) — with two KeyResolver beans and no @Primary that autowiring is
     * ambiguous and fails ApplicationContext startup outright. Every route below does specify
     * its resolver explicitly, so this default is never actually used in practice.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst("X-User-Id"))
                .defaultIfEmpty("anonymous");
    }

    /**
     * Used only for the login/refresh/logout route (see application.yml), where there's no
     * X-User-Id yet — every unauthenticated caller would otherwise share one "anonymous"
     * bucket, so a single credential-stuffing script could exhaust everyone else's login
     * attempts. Keying by remote address gives each caller their own bucket at a much
     * stricter rate than general API traffic.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }
}
