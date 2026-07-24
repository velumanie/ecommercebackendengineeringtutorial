package com.ecommerce.user.service.impl;

import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.LoginResponse;
import com.ecommerce.user.dto.RefreshTokenRequest;
import com.ecommerce.user.entity.RefreshToken;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.exception.AccountLockedException;
import com.ecommerce.user.exception.InvalidCredentialsException;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.JwtService;
import com.ecommerce.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final long REFRESH_TTL_DAYS = 7;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        // Checked before the password comparison so a locked-out account can't keep being
        // probed for free. This does reveal that the email exists (a locked response is
        // distinguishable from "wrong password"), a standard, accepted tradeoff — the
        // alternative is telling a legitimately locked-out user nothing about why they can't
        // log in.
        if (user.isLocked()) {
            throw new AccountLockedException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin();
            userRepository.save(user);
            throw new InvalidCredentialsException();
        }

        user.recordSuccessfulLogin();
        userRepository.save(user);

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = issueRefreshToken(user.getId());
        return new LoginResponse(accessToken, refreshToken, jwtService.accessTtlSeconds());
    }

    @Override
    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {
        String hash = hash(request.refreshToken());
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .filter(RefreshToken::isValid)
                .orElseThrow(InvalidCredentialsException::new);

        User user = userRepository.findById(token.getUserId()).orElseThrow(InvalidCredentialsException::new);
        String accessToken = jwtService.generateAccessToken(user);
        return new LoginResponse(accessToken, request.refreshToken(), jwtService.accessTtlSeconds());
    }

    @Override
    @Transactional
    public void revoke(RefreshTokenRequest request) {
        refreshTokenRepository.findByTokenHash(hash(request.refreshToken()))
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    private String issueRefreshToken(UUID userId) {
        String raw = UUID.randomUUID() + "." + UUID.randomUUID();
        RefreshToken token = RefreshToken.issue(userId, hash(raw), Instant.now().plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS));
        refreshTokenRepository.save(token);
        return raw;
    }

    private String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes());
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
