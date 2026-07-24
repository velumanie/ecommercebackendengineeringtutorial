package com.ecommerce.user.dto;

import com.ecommerce.user.entity.UserStatus;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        UserStatus status,
        Set<String> roles,
        Instant createdAt) {
}
