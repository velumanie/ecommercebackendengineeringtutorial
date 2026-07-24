package com.ecommerce.gateway.filter;

import com.ecommerce.common.constants.CorrelationIdConstants;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void mintsNewCorrelationId_whenCallerSuppliedNone() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        String responseHeader = exchange.getResponse().getHeaders().getFirst(CorrelationIdConstants.HEADER);
        assertThat(responseHeader).isNotBlank();
        verify(chain).filter(any(ServerWebExchange.class));
    }

    @Test
    void reusesCallerSuppliedCorrelationId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header(CorrelationIdConstants.HEADER, "caller-supplied-id"));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdConstants.HEADER))
                .isEqualTo("caller-supplied-id");
    }
}
