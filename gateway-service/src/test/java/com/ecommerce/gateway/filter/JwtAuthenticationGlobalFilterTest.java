package com.ecommerce.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthenticationGlobalFilterTest {

    private static final String SECRET = "test-only-signing-secret-that-is-at-least-64-bytes-long-0123456789";

    private final JwtAuthenticationGlobalFilter filter = new JwtAuthenticationGlobalFilter(SECRET);

    @Test
    void bypassesAuth_forPublicPath() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/users/auth/login"));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rejects_whenAuthorizationHeaderMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    @Test
    void rejects_whenTokenSignedWithWrongSecret() throws Exception {
        String token = signToken(List.of("CUSTOMER"), "an-entirely-different-signing-secret-that-is-also-64-bytes-or-more-0123456789");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header("Authorization", "Bearer " + token));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    @Test
    void forwardsRequest_andInjectsUserHeaders_forValidToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = signToken(userId, List.of("CUSTOMER", "MANAGER"), SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/orders").header("Authorization", "Bearer " + token));
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerWebExchange forwarded = captor.getValue();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(userId.toString());
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("CUSTOMER,MANAGER");
    }

    private String signToken(List<String> roles, String secret) throws Exception {
        return signToken(UUID.randomUUID(), roles, secret);
    }

    private String signToken(UUID subject, List<String> roles, String secret) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}
