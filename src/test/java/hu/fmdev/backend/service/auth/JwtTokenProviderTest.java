package hu.fmdev.backend.service.auth;

import hu.fmdev.backend.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        // 256-bit key in Base64
        config.setSecret(Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-32bytes!".getBytes()));
        config.setAccessTokenExpirationMs(900_000);
        config.setRefreshTokenExpirationMs(604_800_000);
        tokenProvider = new JwtTokenProvider(config);
    }

    @Test
    void generateAccessToken_containsUsername() {
        String token = tokenProvider.generateAccessToken("testuser", Set.of("ADMIN"));

        String username = tokenProvider.getUsernameFromToken(token);
        assertEquals("testuser", username);
    }

    @Test
    void generateAccessToken_containsAuthorities() {
        Set<String> authorities = Set.of("CONTRACTS_EDIT", "CONTRACTS_READ");
        String token = tokenProvider.generateAccessToken("testuser", authorities);

        Set<String> extracted = tokenProvider.getAuthoritiesFromToken(token);
        assertEquals(authorities, extracted);
    }

    @Test
    void generateRefreshToken_containsUsername() {
        String token = tokenProvider.generateRefreshToken("testuser");

        String username = tokenProvider.getUsernameFromToken(token);
        assertEquals("testuser", username);
    }

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = tokenProvider.generateAccessToken("testuser", Set.of());

        assertTrue(tokenProvider.validateToken(token));
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(tokenProvider.validateToken("invalid.token.here"));
    }

    @Test
    void validateToken_nullToken_returnsFalse() {
        assertFalse(tokenProvider.validateToken(null));
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        JwtConfig config = new JwtConfig();
        config.setSecret(Base64.getEncoder().encodeToString(
                "this-is-a-test-secret-key-32bytes!".getBytes()));
        config.setAccessTokenExpirationMs(0); // expires immediately
        config.setRefreshTokenExpirationMs(604_800_000);
        JwtTokenProvider shortLivedProvider = new JwtTokenProvider(config);

        String token = shortLivedProvider.generateAccessToken("testuser", Set.of());

        assertFalse(shortLivedProvider.validateToken(token));
    }

    @Test
    void getAuthoritiesFromToken_refreshToken_returnsEmpty() {
        String token = tokenProvider.generateRefreshToken("testuser");

        Set<String> authorities = tokenProvider.getAuthoritiesFromToken(token);
        assertTrue(authorities.isEmpty());
    }

    @Test
    void generateAccessToken_emptyAuthorities() {
        String token = tokenProvider.generateAccessToken("testuser", Set.of());

        Set<String> extracted = tokenProvider.getAuthoritiesFromToken(token);
        assertTrue(extracted.isEmpty());
    }
}
