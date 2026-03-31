package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.domain.User;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Determines which PST files the current authenticated user may access.
 * Returns null when there is no restriction (admin or no list configured).
 */
@Service
public class FileAccessService {

    private final UserRepository userRepository;
    private final FileInfoRepository fileInfoRepository;

    public FileAccessService(UserRepository userRepository, FileInfoRepository fileInfoRepository) {
        this.userRepository = userRepository;
        this.fileInfoRepository = fileInfoRepository;
    }

    /**
     * Returns the set of allowed PST file names for the current user,
     * or null if the user has unrestricted access.
     */
    public Set<String> getAllowedPstFileNames() {
        Set<String> allowedIds = getAllowedFileInfoIds();
        if (allowedIds == null) return null;

        return allowedIds.stream()
                .map(fileInfoRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(FileInfo::getFileName)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the set of allowed FileInfo IDs for the current user,
     * or null if the user has unrestricted access.
     */
    public Set<String> getAllowedFileInfoIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;

        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) return null;

        User user = userRepository.findByUsername(auth.getName());
        if (user == null) return null;

        List<String> allowedIds = user.getAllowedFileInfoIds();
        if (allowedIds == null || allowedIds.isEmpty()) return null;

        return new HashSet<>(allowedIds);
    }
}
