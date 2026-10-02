package cn.jehorstudio.minetale.narrative.data;

public final class NarrativeValidationException extends RuntimeException {
    public NarrativeValidationException(String message) {
        super(message);
    }

    public NarrativeValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
