package hu.fmdev.backend.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CentralLogger {
    private static final Logger logger = LoggerFactory.getLogger(CentralLogger.class);

    public static void logInfo(String message) {
        logger.info(message);
    }

    public static void logDebug(String message) {
        logger.debug(message);
    }

    public static void logError(String message, Throwable t) {
        logger.error(message, t);
    }
}