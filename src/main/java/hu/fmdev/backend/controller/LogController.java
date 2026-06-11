package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.LogEntry;
import hu.fmdev.backend.repository.LogEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogEntryRepository logRepo;

    public LogController(LogEntryRepository logRepo) {
        this.logRepo = logRepo;
    }

    @GetMapping
    public List<LogEntry> getLogs(
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "300") int limit,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        Instant f = from != null ? Instant.parse(from) : Instant.EPOCH;
        Instant t = to   != null ? Instant.parse(to)   : Instant.now().plusSeconds(60);
        Sort.Direction dir = "asc".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var page = PageRequest.of(0, limit, Sort.by(dir, "timestamp"));

        return level != null
                ? logRepo.findByLevelAndTimestampBetween(level, f, t, page)
                : logRepo.findByTimestampBetween(f, t, page);
    }
}
