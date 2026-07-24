package com.ecommerce.notification.controller;

import com.ecommerce.notification.dto.EmailLogResponse;
import com.ecommerce.notification.entity.EmailLog;
import com.ecommerce.notification.entity.EmailStatus;
import com.ecommerce.notification.mapper.EmailLogMapper;
import com.ecommerce.notification.repository.EmailLogRepository;
import com.ecommerce.notification.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EmailLogController.class)
@Import(SecurityConfig.class)
class EmailLogControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;

    @MockitoBean private EmailLogRepository emailLogRepository;
    @MockitoBean private EmailLogMapper emailLogMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void list_returns200_forManager() throws Exception {
        EmailLog log = EmailLog.queue(UUID.randomUUID(), "jane@example.com", "Order confirmed");
        when(emailLogRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 20), 1));
        when(emailLogMapper.toResponse(log)).thenReturn(
                new EmailLogResponse(UUID.randomUUID(), "jane@example.com", "Order confirmed", EmailStatus.SENT, null));

        mockMvc.perform(get("/api/v1/notifications/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recipient").value("jane@example.com"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void list_returns403_forCustomer() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/emails"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_returns401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/emails"))
                .andExpect(status().isUnauthorized());
    }
}
