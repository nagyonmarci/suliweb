package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Authority;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthorityRepository extends MongoRepository<Authority, String> {
    Authority findByPermission(String permission);
}
