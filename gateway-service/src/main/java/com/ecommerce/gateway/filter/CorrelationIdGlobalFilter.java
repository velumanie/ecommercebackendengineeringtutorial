package com.ecommerce.gateway.filter;

import com.ecommerce.common.constants.CorrelationIdConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * First filter in the chain: every request gets a correlation id — reused if the caller
 * already supplied one, minted otherwise — so it's present for every filter and every
 * downstream service from this point on.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdConstants.HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(CorrelationIdConstants.HEADER, correlationId)
                .build();
        exchange.getResponse().getHeaders().add(CorrelationIdConstants.HEADER, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
