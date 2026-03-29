package hu.fmdev.backend.service.auth;

import hu.fmdev.backend.domain.Authority;
import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.AuthorityRepository;
import hu.fmdev.backend.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class UserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;

    public UserSeeder(UserRepository userRepository,
                      AuthorityRepository authorityRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        Authority adminAuth = ensureAuthority("ROLE_ADMIN");
        Authority userAuth  = ensureAuthority("ROLE_USER");
        Authority ragChat   = ensureAuthority("RAG_CHAT");

        if (userRepository.count() == 0) {
            System.out.println("No users found. Creating default admin user...");

            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setEmail("admin@example.com");
            Set<Authority> authorities = new HashSet<>();
            authorities.add(adminAuth);
            authorities.add(userAuth);
            authorities.add(ragChat);
            admin.setAuthorities(authorities);

            userRepository.save(admin);
            System.out.println("Default admin user created successfully.");
        }
    }

    private Authority ensureAuthority(String permission) {
        Authority existing = authorityRepository.findByPermission(permission);
        if (existing != null) return existing;
        Authority auth = new Authority();
        auth.setPermission(permission);
        return authorityRepository.save(auth);
    }
}
