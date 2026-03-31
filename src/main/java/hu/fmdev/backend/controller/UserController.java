package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.Authority;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.AuthorityRepository;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
public class UserController {

    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;
    private final FileInfoRepository fileInfoRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
                          AuthorityRepository authorityRepository,
                          FileInfoRepository fileInfoRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserDto> listUsers() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUser(@PathVariable String id) {
        return userRepository.findById(id)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request) {
        if (userRepository.findByUsername(request.username()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "A felhasználónév már foglalt"));
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(request.email());

        if (request.authorityIds() != null && !request.authorityIds().isEmpty()) {
            Set<Authority> authorities = request.authorityIds().stream()
                    .map(authorityRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            user.setAuthorities(authorities);
        }

        User saved = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(@PathVariable String id, @RequestBody UpdateUserRequest request) {
        Optional<User> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User user = opt.get();

        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.password()));
        }
        if (request.authorityIds() != null) {
            Set<Authority> authorities = request.authorityIds().stream()
                    .map(authorityRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toSet());
            user.setAuthorities(authorities);
        }

        return ResponseEntity.ok(toDto(userRepository.save(user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        if (!userRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        userRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/authorities")
    public List<AuthorityDto> listAuthorities() {
        return authorityRepository.findAll().stream()
                .map(a -> new AuthorityDto(a.getId(), a.getPermission()))
                .collect(Collectors.toList());
    }

    @PutMapping("/{id}/files")
    public ResponseEntity<?> updateAllowedFiles(@PathVariable String id, @RequestBody UpdateFilesRequest request) {
        Optional<User> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = opt.get();
        user.setAllowedFileInfoIds(request.fileInfoIds() != null ? request.fileInfoIds() : new ArrayList<>());
        return ResponseEntity.ok(toDto(userRepository.save(user)));
    }

    @GetMapping("/{id}/files")
    public ResponseEntity<List<FileInfoDto>> getAllowedFiles(@PathVariable String id) {
        Optional<User> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<String> allowedIds = opt.get().getAllowedFileInfoIds();
        if (allowedIds == null || allowedIds.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<FileInfoDto> files = allowedIds.stream()
                .map(fileInfoRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(f -> new FileInfoDto(f.getId(), f.getFileName(), f.getPath(), f.getStatus()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(files);
    }

    private UserDto toDto(User user) {
        List<String> permissions = user.getAuthorities().stream()
                .map(Authority::getPermission)
                .collect(Collectors.toList());
        List<String> authorityIds = user.getAuthorities().stream()
                .map(Authority::getId)
                .collect(Collectors.toList());
        List<String> allowedFileInfoIds = user.getAllowedFileInfoIds() != null
                ? user.getAllowedFileInfoIds()
                : List.of();
        return new UserDto(user.getId(), user.getUsername(), user.getEmail(), permissions, authorityIds, allowedFileInfoIds);
    }

    public record UserDto(String id, String username, String email, List<String> authorities, List<String> authorityIds, List<String> allowedFileInfoIds) {}
    public record AuthorityDto(String id, String permission) {}
    public record FileInfoDto(String id, String fileName, String path, String status) {}
    public record CreateUserRequest(String username, String password, String email, List<String> authorityIds) {}
    public record UpdateUserRequest(String email, String password, List<String> authorityIds) {}
    public record UpdateFilesRequest(List<String> fileInfoIds) {}
}
