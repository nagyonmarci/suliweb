package hu.fmdev.backend.controller;

import hu.fmdev.backend.domain.LogEntry;
import hu.fmdev.backend.repository.LogEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "300") int limit) {
        var page = PageRequest.of(0, limit);
        return level != null
                ? logRepo.findByLevelOrderByTimestampDesc(level, page)
                : logRepo.findAllByOrderByTimestampDesc(page);
    }
}
