package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String> {

    List<DocumentChunk> findByEmailId(String emailId);

    boolean existsByEmailIdAndSourceTypeAndChunkIndex(String emailId, String sourceType, int chunkIndex);

    long countByIngestionStatus(String status);

    List<DocumentChunk> findByIngestionStatus(String status);

    void deleteByEmailId(String emailId);
}
