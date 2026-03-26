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
        if (userRepository.count() == 0) {
            System.out.println("No users found. Creating default admin user...");

            // Create Authorities
            Authority adminAuth = new Authority();
            adminAuth.setPermission("ROLE_ADMIN");
            authorityRepository.save(adminAuth);

            Authority userAuth = new Authority();
            userAuth.setPermission("ROLE_USER");
            authorityRepository.save(userAuth);

            // Create Admin User
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setEmail("admin@example.com");
            Set<Authority> authorities = new HashSet<>();
            authorities.add(adminAuth);
            authorities.add(userAuth);
            admin.setAuthorities(authorities);
            
            userRepository.save(admin);
            System.out.println("Default admin user created successfully.");
        }
    }
}
