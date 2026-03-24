package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.UserRepository;
import hu.fmdev.backend.service.auth.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authenticationManager, jwtTokenProvider,
                userRepository, passwordEncoder);
    }

    // --- Register ---

    @Test
    void register_newUser_returnsCreated() {
        when(userRepository.findByUsername("newuser")).thenReturn(null);
        when(passwordEncoder.encode("password")).thenReturn("$2a$hashedpassword");

        var request = new AuthController.RegisterRequest("newuser", "password", "test@test.com");
        var response = controller.register(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_existingUser_returnsConflict() {
        when(userRepository.findByUsername("existing")).thenReturn(new User());

        var request = new AuthController.RegisterRequest("existing", "password", "test@test.com");
        var response = controller.register(request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    // --- Login ---

    @Test
    void login_validCredentials_returnsTokens() {
        var authorities = List.of(new SimpleGrantedAuthority("ADMIN"));
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass", authorities);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtTokenProvider.generateAccessToken(eq("user"), anySet())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken("user")).thenReturn("refresh-token");

        var request = new AuthController.LoginRequest("user", "pass");
        var response = controller.login(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("access-token", response.getBody().get("accessToken"));
        assertEquals("refresh-token", response.getBody().get("refreshToken"));
        assertEquals("Bearer", response.getBody().get("tokenType"));
    }

    @Test
    void login_invalidCredentials_throwsException() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        var request = new AuthController.LoginRequest("user", "wrong");

        assertThrows(BadCredentialsException.class, () -> controller.login(request));
    }

    // --- Refresh ---

    @Test
    void refresh_validToken_returnsNewAccessToken() {
        when(jwtTokenProvider.validateToken("valid-refresh")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid-refresh")).thenReturn("user");
        User user = new User();
        user.setUsername("user");
        when(userRepository.findByUsername("user")).thenReturn(user);
        when(jwtTokenProvider.generateAccessToken(eq("user"), anySet())).thenReturn("new-access");

        var request = new AuthController.RefreshRequest("valid-refresh");
        var response = controller.refresh(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("new-access", response.getBody().get("accessToken"));
    }

    @Test
    void refresh_invalidToken_returnsUnauthorized() {
        when(jwtTokenProvider.validateToken("invalid")).thenReturn(false);

        var request = new AuthController.RefreshRequest("invalid");
        var response = controller.refresh(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    // --- Me ---

    @Test
    void me_authenticated_returnsUserInfo() {
        var authorities = List.of(new SimpleGrantedAuthority("ADMIN"));
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null, authorities);

        User user = new User();
        user.setUsername("user");
        user.setEmail("user@test.com");
        when(userRepository.findByUsername("user")).thenReturn(user);

        var response = controller.me(auth);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("user", response.getBody().get("username"));
        assertEquals("user@test.com", response.getBody().get("email"));
    }

    @Test
    void me_notAuthenticated_returnsUnauthorized() {
        var response = controller.me(null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
