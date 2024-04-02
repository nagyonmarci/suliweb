package hu.fmdev.backend.exceptionhandler;

public class PstProcessingException extends RuntimeException {
    public PstProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}