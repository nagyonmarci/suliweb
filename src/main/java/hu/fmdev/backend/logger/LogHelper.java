package hu.fmdev.backend.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogHelper.class);

    public static void logError(Logger logger, String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    public static void logWarn(Logger logger, String message, Object... objects) {
        logger.warn(message, objects);
    }

    public static void logInfo(Logger logger, String message) {
        logger.info(message);
    }

    public static void logDebug(Logger logger, String message) {
        logger.debug(message);
    }

    private static final String ERROR_PROCESSING_PST_FILE = "Error processing PST file";
    private static final String TEMP_FILE_NOT_DELETED = "Temporary file could not be deleted: {}";
    private static final String ERROR_PROCESSING_PST_FILES_FROM_TXT = "Error processing PST files from TXT";
    private static final String ERROR_PROCESSING_PST_FILES_FROM_DB = "Error processing PST files from database";

    public static void logErrorProcessingPstFile(Logger logger, Throwable throwable) {
        logger.error(ERROR_PROCESSING_PST_FILE, throwable);
    }

    public static void logTempFileNotDeleted(Logger logger, String filePath) {
        logger.warn(TEMP_FILE_NOT_DELETED, filePath);
    }

    public static void logErrorProcessingPstFilesFromTxt(Logger logger, Throwable throwable) {
        logger.error(ERROR_PROCESSING_PST_FILES_FROM_TXT, throwable);
    }

    public static void logErrorProcessingPstFilesFromDb(Logger logger, Throwable throwable) {
        logger.error(ERROR_PROCESSING_PST_FILES_FROM_DB, throwable);
    }
}
