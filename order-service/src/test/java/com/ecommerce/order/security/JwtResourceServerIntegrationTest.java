package com.ecommerce.order.security;

import com.ecommerce.common.idempotency.IdempotentResult;
import com.ecommerce.order.controller.OrderController;
import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unlike {@code OrderControllerTest}, which uses {@code @WithMockUser} to bypass token
 * parsing entirely, this exercises the actual {@link org.springframework.security.oauth2.jwt.JwtDecoder}
 * bean against a real HMAC-signed token — proving the resource server wiring (not just the
 * authorization rules) works end to end, the same way user-service's issued tokens would.
 */
@WebMvcTest(controllers = OrderController.class)
@Import(SecurityConfig.class)
class JwtResourceServerIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Value("${security.jwt.secret}") private String jwtSecret;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private OrderService orderService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void createOrder_acceptsGenuineJwt_signedWithSharedSecret() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));
        var response = new OrderResponse(UUID.randomUUID(), request.customerId(), OrderStatus.CONFIRMED,
                BigDecimal.TEN, List.of(), null, null);
        when(orderService.createOrder(any(), any())).thenReturn(IdempotentResult.created(response));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + signToken(List.of("CUSTOMER")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void createOrder_rejects_tokenSignedWithWrongSecret() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + signToken(List.of("CUSTOMER"), "an-entirely-different-signing-secret-that-is-also-64-bytes-or-more-0123456789"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOrder_rejects_expiredToken() throws Exception {
        var request = new OrderRequest(UUID.randomUUID(), List.of(new OrderItemRequest(UUID.randomUUID(), 1)));

        mockMvc.perform(post("/api/v1/orders")
                        .header("Authorization", "Bearer " + signExpiredToken(List.of("CUSTOMER")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    private String signToken(List<String> roles) throws Exception {
        return signToken(roles, jwtSecret);
    }

    private String signToken(List<String> roles, String secret) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }

    private String signExpiredToken(List<String> roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now().minusSeconds(3600)))
                .expirationTime(Date.from(Instant.now().minusSeconds(1800)))
                .build();
        SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        signedJwt.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));
        return signedJwt.serialize();
    }
}
