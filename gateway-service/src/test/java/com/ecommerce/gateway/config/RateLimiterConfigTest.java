package com.ecommerce.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void resolvesToXUserIdHeader_whenPresent() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header("X-User-Id", "user-123"));

        String key = config.userKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("user-123");
    }

    @Test
    void fallsBackToAnonymous_whenHeaderMissing() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/products"));

        String key = config.userKeyResolver().resolve(exchange).block();

        assertThat(key).isEqualTo("anonymous");
    }
}
