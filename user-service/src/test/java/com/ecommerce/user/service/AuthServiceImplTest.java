package com.ecommerce.user.service;

import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.exception.AccountLockedException;
import com.ecommerce.user.exception.InvalidCredentialsException;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.JwtService;
import com.ecommerce.user.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void login_throwsInvalidCredentials_whenPasswordDoesNotMatch() {
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("jane@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_issuesTokens_whenCredentialsMatch() {
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.accessTtlSeconds()).thenReturn(900L);

        var response = authService.login(new LoginRequest("jane@example.com", "correct"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void login_locksAccount_afterFifthConsecutiveFailedAttempt() {
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("jane@example.com", "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(user.isLocked()).isFalse();

        assertThatThrownBy(() -> authService.login(new LoginRequest("jane@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.isLocked()).isTrue();
        verify(userRepository, org.mockito.Mockito.times(5)).save(user);
    }

    @Test
    void login_throwsAccountLocked_whenAlreadyLockedOut() {
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin();
        }
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("jane@example.com", "whatever")))
                .isInstanceOf(AccountLockedException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void login_resetsFailedAttempts_onSuccessfulLogin() {
        User user = User.create("jane@example.com", "hashed", "Jane", "Doe");
        user.recordFailedLogin();
        user.recordFailedLogin();
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.accessTtlSeconds()).thenReturn(900L);

        authService.login(new LoginRequest("jane@example.com", "correct"));

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.isLocked()).isFalse();
    }

    @Test
    void login_doesNotLockAccount_forUnknownEmail() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).save(any());
    }
}
