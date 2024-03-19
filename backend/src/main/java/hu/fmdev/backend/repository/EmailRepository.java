package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.Email;
import org.springframework.data.mongodb.repository.MongoRepository;


public interface EmailRepository extends MongoRepository<Email, String> {
    // Itt definiálhatók további lekérdezések, ha szükséges
}