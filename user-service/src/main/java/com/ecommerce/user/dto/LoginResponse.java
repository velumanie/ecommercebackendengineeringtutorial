package com.ecommerce.user.dto;

public record LoginResponse(String accessToken, String refreshToken, long expiresInSeconds) {
}
