package com.ecommerce.user.mapper;

import com.ecommerce.user.dto.UserResponse;
import com.ecommerce.user.entity.Role;
import com.ecommerce.user.entity.User;
import org.mapstruct.Mapper;

import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getStatus(), toRoleNames(user.getRoles()), user.getCreatedAt());
    }

    private Set<String> toRoleNames(Set<Role> roles) {
        return roles.stream().map(Role::getName).collect(Collectors.toSet());
    }
}
