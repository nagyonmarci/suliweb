package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.LogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface LogEntryRepository extends MongoRepository<LogEntry, String> {
    List<LogEntry> findByTimestampBetween(Instant from, Instant to, Pageable pageable);
    List<LogEntry> findByLevelAndTimestampBetween(String level, Instant from, Instant to, Pageable pageable);
}
