package com.ecommerce.product.controller;

import com.ecommerce.product.dto.PageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.entity.ProductStatus;
import com.ecommerce.product.exception.ProductNotFoundException;
import com.ecommerce.product.security.SecurityConfig;
import com.ecommerce.product.service.ProductService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private ProductService productService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void list_isPublic_noAuthRequired() throws Exception {
        when(productService.list(any(), any(), any())).thenReturn(
                new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void create_returns201_forManager() throws Exception {
        var request = new ProductRequest("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);
        var response = new ProductResponse(UUID.randomUUID(), "SKU-1", "Widget", "A widget",
                new BigDecimal("19.99"), null, ProductStatus.ACTIVE);
        when(productService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-1"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void create_returns403_forCustomer() throws Exception {
        var request = new ProductRequest("SKU-1", "Widget", "A widget", new BigDecimal("19.99"), null);

        mockMvc.perform(post("/api/v1/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void create_returns400_onInvalidPrice() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType("application/json")
                        .content("{\"sku\":\"SKU-1\",\"name\":\"Widget\",\"price\":-5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(productService.get(id)).thenThrow(new ProductNotFoundException(id));

        mockMvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_returns204_forAdmin() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void delete_returns403_forManager() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/products/{id}", id))
                .andExpect(status().isForbidden());
    }
}
