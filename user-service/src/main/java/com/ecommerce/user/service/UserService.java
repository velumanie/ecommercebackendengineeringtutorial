package com.ecommerce.user.service;

import com.ecommerce.user.dto.CreateUserRequest;
import com.ecommerce.user.dto.PageResponse;
import com.ecommerce.user.dto.UpdateUserRequest;
import com.ecommerce.user.dto.UserResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserService {
    UserResponse create(CreateUserRequest request);
    UserResponse get(UUID id);
    PageResponse<UserResponse> list(Pageable pageable);
    UserResponse update(UUID id, UpdateUserRequest request);
    void delete(UUID id);
}
