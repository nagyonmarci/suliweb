package hu.fmdev.backend.logger;

import hu.fmdev.backend.domain.LogEntry;
import hu.fmdev.backend.repository.LogEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

@Component
public class CentralLogger {

    private static final Logger logger = LoggerFactory.getLogger(CentralLogger.class);

    @Autowired
    private LogEntryRepository logEntryRepository;

    private static CentralLogger instance;

    @PostConstruct
    public void init() {
        instance = this;
    }

    public static void logInfo(String message) {
        logger.info(message);
        if (instance != null) {
            instance.saveLogEntry("INFO", message, null);
        }
    }

    public static void logDebug(String message) {
        logger.debug(message);
        if (instance != null) {
            instance.saveLogEntry("DEBUG", message, null);
        }
    }

    public static void logWarn(String message) {
        logger.warn(message);
        if (instance != null) {
            instance.saveLogEntry("WARN", message, null);
        }
    }

    public static void logError(String message, Throwable t) {
        logger.error(message, t);
        if (instance != null) {
            String stackTrace = null;
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                stackTrace = sw.toString();
            }
            instance.saveLogEntry("ERROR", message, stackTrace);
        }
    }

    private void saveLogEntry(String level, String message, String stackTrace) {
        LogEntry logEntry = new LogEntry(Instant.now(), level, message, stackTrace);
        logEntryRepository.save(logEntry);
    }
}
