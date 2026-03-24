package hu.fmdev.backend.service.auth;

import hu.fmdev.backend.domain.Authority;
import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private MongoUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new MongoUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_existingUser_returnsUserDetails() {
        Authority auth = new Authority();
        auth.setPermission("CONTRACTS_EDIT");

        User user = new User();
        user.setUsername("testuser");
        user.setPassword("hashedpassword");
        user.setAuthorities(Set.of(auth));

        when(userRepository.findByUsername("testuser")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("testuser");

        assertEquals("testuser", details.getUsername());
        assertEquals("hashedpassword", details.getPassword());
        assertEquals(1, details.getAuthorities().size());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("CONTRACTS_EDIT")));
    }

    @Test
    void loadUserByUsername_nonExistingUser_throwsException() {
        when(userRepository.findByUsername("unknown")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown"));
    }

    @Test
    void loadUserByUsername_userWithNoAuthorities_returnsEmptyAuthorities() {
        User user = new User();
        user.setUsername("noauth");
        user.setPassword("pass");

        when(userRepository.findByUsername("noauth")).thenReturn(user);

        UserDetails details = service.loadUserByUsername("noauth");

        assertEquals("noauth", details.getUsername());
        assertTrue(details.getAuthorities().isEmpty());
    }
}
