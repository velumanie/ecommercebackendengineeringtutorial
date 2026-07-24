package com.ecommerce.gateway.filter;

import com.ecommerce.common.constants.CorrelationIdConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestLoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdConstants.HEADER);
        String path = exchange.getRequest().getPath().value();

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("correlationId={} method={} path={} status={} durationMs={}",
                    correlationId, exchange.getRequest().getMethod(), path, status, durationMs);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
