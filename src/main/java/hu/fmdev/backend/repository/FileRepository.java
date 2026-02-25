package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.FileEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileRepository extends MongoRepository<FileEntity, String> {
    Optional<FileEntity> findByPath(String path);
    List<FileEntity> findByPathStartingWith(String path);
}
