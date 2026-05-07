package com.circleguard.gateway.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * UNIT TESTS for QrValidationService (gateway-service).
 * Validates the four key branches of campus-entry decision logic without
 * starting Redis or Spring.
 */
@Tag("unit")
class QrValidationServiceUnitTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-chars-1234";
    private QrValidationService service;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> ops;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        service = new QrValidationService(redisTemplate);
        ReflectionTestUtils.setField(service, "qrSecret", SECRET);
    }

    private String signToken(UUID anonymousId, long lifetimeMs) {
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + lifetimeMs))
                .signWith(key)
                .compact();
    }

    @Test
    @DisplayName("Unit-1: a valid token for a CLEAR user returns GREEN")
    void clearUserGetsGreen() {
        UUID id = UUID.randomUUID();
        when(ops.get("user:status:" + id)).thenReturn("CLEAR");

        QrValidationService.ValidationResult r = service.validateToken(signToken(id, 60_000));

        assertThat(r.valid()).isTrue();
        assertThat(r.status()).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("Unit-2: CONTAGIED status forces RED denial")
    void contagiedUserDenied() {
        UUID id = UUID.randomUUID();
        when(ops.get("user:status:" + id)).thenReturn("CONTAGIED");

        QrValidationService.ValidationResult r = service.validateToken(signToken(id, 60_000));

        assertThat(r.valid()).isFalse();
        assertThat(r.status()).isEqualTo("RED");
        assertThat(r.message()).contains("Health Risk");
    }

    @Test
    @DisplayName("Unit-3: POTENTIAL status (suspected contact) is also RED")
    void potentialUserDenied() {
        UUID id = UUID.randomUUID();
        when(ops.get("user:status:" + id)).thenReturn("POTENTIAL");

        QrValidationService.ValidationResult r = service.validateToken(signToken(id, 60_000));

        assertThat(r.valid()).isFalse();
        assertThat(r.status()).isEqualTo("RED");
    }

    @Test
    @DisplayName("Unit-4: an expired token is rejected")
    void expiredTokenRejected() {
        UUID id = UUID.randomUUID();
        // Issued already-expired (-1s).
        QrValidationService.ValidationResult r = service.validateToken(signToken(id, -1000));

        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("Invalid or Expired");
    }

    @Test
    @DisplayName("Unit-5: a tampered/garbage token is rejected without leaking exceptions")
    void tamperedTokenRejected() {
        QrValidationService.ValidationResult r = service.validateToken("not.a.real.jwt");

        assertThat(r.valid()).isFalse();
        assertThat(r.status()).isEqualTo("RED");
        assertThat(r.message()).isNotBlank();
    }
}
