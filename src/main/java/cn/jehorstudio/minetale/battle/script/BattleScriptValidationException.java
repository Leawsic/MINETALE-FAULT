package cn.jehorstudio.minetale.battle.script;

public final class BattleScriptValidationException extends RuntimeException {
    private final BattleScriptDiagnosticCode code;
    private final String path;
    private final String humanMessage;

    public BattleScriptValidationException(String message) {
        this(BattleScriptDiagnosticCode.infer(message), inferPath(message), message, null);
    }

    public BattleScriptValidationException(String message, Throwable cause) {
        this(BattleScriptDiagnosticCode.infer(message), inferPath(message), message, cause);
    }

    public BattleScriptValidationException(
            BattleScriptDiagnosticCode code,
            String path,
            String humanMessage
    ) {
        this(code, path, humanMessage, null);
    }

    public BattleScriptValidationException(
            BattleScriptDiagnosticCode code,
            String path,
            String humanMessage,
            Throwable cause
    ) {
        super(humanMessage, cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
        this.path = java.util.Objects.requireNonNull(path, "path");
        this.humanMessage = java.util.Objects.requireNonNull(humanMessage, "humanMessage");
    }

    public BattleScriptDiagnosticCode code() {
        return this.code;
    }

    public String path() {
        return this.path;
    }

    public String humanMessage() {
        return this.humanMessage;
    }

    private static String inferPath(String message) {
        if (message == null || message.isBlank()) return "$";
        int delimiter = message.indexOf(' ');
        String candidate = delimiter < 0 ? message : message.substring(0, delimiter);
        return candidate.replaceAll("[.:]+$", "");
    }
}
