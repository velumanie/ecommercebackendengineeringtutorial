package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.ReserveStockRequest;
import com.ecommerce.inventory.dto.SetStockRequest;
import com.ecommerce.inventory.dto.StockAvailabilityResponse;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.StockNotFoundException;
import com.ecommerce.inventory.security.SecurityConfig;
import com.ecommerce.inventory.service.InventoryService;
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

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void checkAvailability_returns200_forCustomer() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.checkAvailability(productId, 2))
                .thenReturn(new StockAvailabilityResponse(productId, true, 10));

        mockMvc.perform(get("/api/v1/inventory/{productId}/availability", productId).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void checkAvailability_returns404_whenNoStockRecord() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.checkAvailability(productId, 2)).thenThrow(new StockNotFoundException(productId));

        mockMvc.perform(get("/api/v1/inventory/{productId}/availability", productId).param("quantity", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void reserve_returns204_forManager() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ReserveStockRequest(2))))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void reserve_returns204_forCustomer() throws Exception {
        // Reserve/release are invoked by order-service on behalf of a customer placing/cancelling
        // an order, so they carry the customer's own token/role — see SecurityConfig — not an
        // elevated one, unlike setStock below which is MANAGER/ADMIN only.
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ReserveStockRequest(2))))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void reserve_returns409_whenInsufficientStock() throws Exception {
        UUID productId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new InsufficientStockException(productId))
                .when(inventoryService).reserve(productId, 100);

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ReserveStockRequest(100))))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void setStock_returns200_forManager() throws Exception {
        UUID productId = UUID.randomUUID();
        when(inventoryService.setStock(productId, 50)).thenReturn(new StockAvailabilityResponse(productId, true, 50));

        mockMvc.perform(post("/api/v1/inventory/{productId}/stock", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new SetStockRequest(50))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityOnHand").value(50));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void setStock_returns403_forCustomer() throws Exception {
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/inventory/{productId}/stock", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new SetStockRequest(50))))
                .andExpect(status().isForbidden());
    }
}
