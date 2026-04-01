package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.FileInfo;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface FileInfoRepository extends MongoRepository<FileInfo, String> {
    Optional<FileInfo> findFirstByPath(String path);

    List<FileInfo> findByStatusIn(List<String> statuses);

    long countByStatus(String status);

    Optional<FileInfo> findFirstByContentHash(String contentHash);
}
