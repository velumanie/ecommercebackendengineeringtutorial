package com.ecommerce.user.service;

import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.LoginResponse;
import com.ecommerce.user.dto.RefreshTokenRequest;

public interface AuthService {
    LoginResponse login(LoginRequest request);
    LoginResponse refresh(RefreshTokenRequest request);
    void revoke(RefreshTokenRequest request);
}
