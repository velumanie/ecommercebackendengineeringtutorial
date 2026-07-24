package com.ecommerce.user.service;

import com.ecommerce.user.dto.CreateUserRequest;
import com.ecommerce.user.dto.UpdateUserRequest;
import com.ecommerce.user.dto.UserResponse;
import com.ecommerce.user.entity.Role;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.entity.UserStatus;
import com.ecommerce.user.exception.EmailAlreadyExistsException;
import com.ecommerce.user.exception.UserNotFoundException;
import com.ecommerce.user.mapper.UserMapper;
import com.ecommerce.user.repository.RoleRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void create_throwsEmailAlreadyExists_whenEmailTaken() {
        var request = new CreateUserRequest("jane@example.com", "password123", "Jane", "Doe", Set.of("CUSTOMER"));
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void create_savesUser_whenEmailAvailable() {
        var request = new CreateUserRequest("jane@example.com", "password123", "Jane", "Doe", Set.of("CUSTOMER"));
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(roleRepository.findByName("CUSTOMER")).thenReturn(Optional.of(new Role("CUSTOMER")));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenReturn(
                new UserResponse(UUID.randomUUID(), "jane@example.com", "Jane", "Doe",
                        UserStatus.ACTIVE, Set.of("CUSTOMER"), null));

        UserResponse response = userService.create(request);

        assertThat(response.email()).isEqualTo("jane@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void create_throwsIllegalArgument_whenRoleUnknown() {
        var request = new CreateUserRequest("jane@example.com", "password123", "Jane", "Doe", Set.of("SUPERADMIN"));
        when(userRepository.existsByEmail("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(roleRepository.findByName("SUPERADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_throws_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.get(id)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void update_updatesNameFields() {
        UUID id = UUID.randomUUID();
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(
                new UserResponse(id, "jane@example.com", "Janet", "Doe", UserStatus.ACTIVE, Set.of(), null));

        UserResponse response = userService.update(id, new UpdateUserRequest("Janet", "Doe"));

        assertThat(user.getFirstName()).isEqualTo("Janet");
        assertThat(response.firstName()).isEqualTo("Janet");
    }

    @Test
    void delete_setsStatusDisabled() {
        UUID id = UUID.randomUUID();
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        userService.delete(id);

        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        verify(userRepository).save(user);
    }
}
