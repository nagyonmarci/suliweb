package hu.fmdev.backend.service.auth;

import hu.fmdev.backend.domain.Authority;
import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.AuthorityRepository;
import hu.fmdev.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class UserSeeder implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

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
            logger.info("No users found. Creating default admin user...");

            String password = (adminPassword != null && !adminPassword.isBlank())
                    ? adminPassword
                    : UUID.randomUUID().toString();

            if (adminPassword == null || adminPassword.isBlank()) {
                logger.warn("ADMIN_PASSWORD environment variable not set. Generated random password: {}", password);
                logger.warn("Set ADMIN_PASSWORD env var to use a fixed password on next startup (after clearing the DB).");
            }

            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(password));
            admin.setEmail("admin@example.com");
            Set<Authority> authorities = new HashSet<>();
            authorities.add(adminAuth);
            authorities.add(userAuth);
            authorities.add(ragChat);
            admin.setAuthorities(authorities);

            userRepository.save(admin);
            logger.info("Default admin user created successfully.");
        }
    }

    private Authority ensureAuthority(String permission) {
        List<Authority> existing = authorityRepository.findByPermission(permission);
        if (existing != null && !existing.isEmpty()) return existing.get(0);
        Authority auth = new Authority();
        auth.setPermission(permission);
        return authorityRepository.save(auth);
    }
}

