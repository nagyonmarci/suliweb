package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.SynologySettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SynologySettingsRepository extends MongoRepository<SynologySettings, String> {
}
