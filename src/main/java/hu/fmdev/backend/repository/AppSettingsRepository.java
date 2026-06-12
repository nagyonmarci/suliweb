package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.AppSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppSettingsRepository extends MongoRepository<AppSettings, String> {
}
