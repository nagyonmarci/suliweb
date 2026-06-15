package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Email;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends MongoRepository<Email, String> {
    Optional<Email> findByUniqueEntryId(String uniqueEntryId);
    boolean existsByUniqueEntryId(String uniqueEntryId);
    List<Email> findByStatusIn(List<String> status);
}
