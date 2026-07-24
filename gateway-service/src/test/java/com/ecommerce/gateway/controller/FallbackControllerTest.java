package com.ecommerce.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = FallbackController.class)
class FallbackControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private WebTestClient webTestClient;

    @Test
    void fallback_returns503_withServiceName() {
        webTestClient.get().uri("/fallback/order-service")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("order-service is not responding; circuit breaker is open");
    }
}
