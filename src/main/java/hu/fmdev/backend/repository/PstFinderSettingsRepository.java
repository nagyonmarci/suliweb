package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.PstFinderSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PstFinderSettingsRepository extends MongoRepository<PstFinderSettings, String> {
}
