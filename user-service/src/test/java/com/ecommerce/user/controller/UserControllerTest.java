package com.ecommerce.user.controller;

import com.ecommerce.user.dto.CreateUserRequest;
import com.ecommerce.user.dto.UpdateUserRequest;
import com.ecommerce.user.dto.UserResponse;
import com.ecommerce.user.entity.UserStatus;
import com.ecommerce.user.exception.UserNotFoundException;
import com.ecommerce.user.security.SecurityConfig;
import com.ecommerce.user.service.UserService;
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

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired private WebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private UserService userService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createUser_returns201() throws Exception {
        var request = new CreateUserRequest("jane@example.com", "password123", "Jane", "Doe", Set.of("CUSTOMER"));
        var response = new UserResponse(UUID.randomUUID(), "jane@example.com", "Jane", "Doe",
                UserStatus.ACTIVE, Set.of("CUSTOMER"), null);
        when(userService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/users")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createUser_returns403_forNonAdmin() throws Exception {
        var request = new CreateUserRequest("jane@example.com", "password123", "Jane", "Doe", Set.of("CUSTOMER"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getUser_returns404_whenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.get(id)).thenThrow(new UserNotFoundException(id));

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateUser_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        var response = new UserResponse(id, "jane@example.com", "Janet", "Doe", UserStatus.ACTIVE, Set.of(), null);
        when(userService.update(eq(id), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/users/{id}", id)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new UpdateUserRequest("Janet", "Doe"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Janet"));
    }
}
