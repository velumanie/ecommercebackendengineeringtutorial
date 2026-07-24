package com.ecommerce.user.security;

import com.ecommerce.user.entity.Role;
import com.ecommerce.user.entity.User;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static io.jsonwebtoken.security.Keys.hmacShaKeyFor;

@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTtlMinutes;

    public JwtService(@Value("${security.jwt.secret}") String secret,
                       @Value("${security.jwt.access-ttl-minutes}") long accessTtlMinutes) {
        this.signingKey = hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public String generateAccessToken(User user) {
        List<String> roles = user.getRoles().stream().map(Role::getName).collect(Collectors.toList());
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}
