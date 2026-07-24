package com.ecommerce.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway is the only edge that browser traffic ever reaches, so response hardening
 * headers belong here rather than duplicated in every downstream service's Spring Security
 * config (those still get their own copy — see each service's SecurityConfig — since calling
 * a service directly, bypassing the gateway, should stay safe too). This is every JSON API
 * response, so the policy is maximally restrictive: nothing here ever renders as HTML.
 */
@Component
public class SecurityHeadersGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.add("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        headers.add("X-Content-Type-Options", "nosniff");
        headers.add("X-Frame-Options", "DENY");
        headers.add("Referrer-Policy", "no-referrer");
        headers.add("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()");
        headers.add("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
