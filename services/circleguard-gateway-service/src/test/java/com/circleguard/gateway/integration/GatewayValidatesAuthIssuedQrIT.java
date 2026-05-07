package com.circleguard.gateway.integration;

import com.circleguard.gateway.service.QrValidationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;

import java.security.Key;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INTEGRATION TEST (Taller 2 - point 3b).
 *
 * Loads the gateway-service Spring context and verifies that a QR JWT signed
 * with the agreed `qr.secret` (the contract maintained jointly by auth-service
 * and gateway-service) is accepted by the gateway. This is the inter-service
 * shared-secret contract test.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "qr.secret=integration-shared-secret-32-chars-min-1234",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=63999"
})
@Tag("integration")
class GatewayValidatesAuthIssuedQrIT {

    private static final String SHARED_SECRET = "integration-shared-secret-32-chars-min-1234";

    @Autowired private QrValidationService validationService;
    @MockBean private StringRedisTemplate redisTemplate;

    private String mintAuthIssuedToken(UUID anonymousId, long lifetimeMs) {
        Key key = Keys.hmacShaKeyFor(SHARED_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId.toString())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + lifetimeMs))
                .signWith(key)
                .compact();
    }

    @Test
    @DisplayName("Int-7: gateway accepts a QR JWT signed by auth-service for a CLEAR user")
    void gatewayAcceptsAuthIssuedQrForCleanUser() {
        UUID anonymousId = UUID.randomUUID();
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("user:status:" + anonymousId)).thenReturn("CLEAR");

        String token = mintAuthIssuedToken(anonymousId, 60_000);
        var result = validationService.validateToken(token);

        assertThat(result.valid()).isTrue();
        assertThat(result.status()).isEqualTo("GREEN");
    }

    @Test
    @DisplayName("Int-8: gateway rejects QR JWT when promotion-service has set status=CONTAGIED in Redis")
    void gatewayRejectsContagiousUser() {
        UUID anonymousId = UUID.randomUUID();
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("user:status:" + anonymousId)).thenReturn("CONTAGIED");

        String token = mintAuthIssuedToken(anonymousId, 60_000);
        var result = validationService.validateToken(token);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("RED");
    }
}
