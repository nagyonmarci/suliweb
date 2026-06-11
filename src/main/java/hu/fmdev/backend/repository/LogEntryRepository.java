package hu.fmdev.backend.repository;

import hu.fmdev.backend.domain.LogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LogEntryRepository extends MongoRepository<LogEntry, String> {
    List<LogEntry> findAllByOrderByTimestampDesc(Pageable pageable);
    List<LogEntry> findByLevelOrderByTimestampDesc(String level, Pageable pageable);
}
