package com.ecommerce.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static io.jsonwebtoken.security.Keys.hmacShaKeyFor;

/**
 * Rejects unauthenticated traffic at the edge, before it reaches any downstream service.
 * Each service still re-validates independently (see order-service's own
 * JwtAuthenticationFilter) so calling a service directly stays safe — the gateway isn't
 * a trust boundary that, once bypassed, grants access.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/users/auth", "/actuator/health", "/swagger-ui", "/v3/api-docs");

    private final SecretKey signingKey;

    public JwtAuthenticationGlobalFilter(@Value("${security.jwt.secret}") String secret) {
        this.signingKey = hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        if (PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        // product-service's own SecurityConfig permits GET /api/v1/products/** without auth
        // (public catalog browsing); mirror that here so the gateway doesn't block it first.
        if (HttpMethod.GET.equals(method) && path.startsWith("/products")) {
            return chain.filter(exchange);
        }

        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(header.substring(7)).getPayload();

            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Roles", String.join(",", roles))
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException e) {
            return unauthorized(exchange);
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
