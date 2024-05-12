import java.util.logging.Level;

public class LoggerHelper {
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(LoggerHelper.class.getName());
    private static LogLevel setLevel = LogLevel.INFO;

    public static void log(LogLevel level, String message) {
        if (setLevel != LogLevel.NONE && level.getLevel().intValue() >= setLevel.getLevel().intValue()) {
            logger.log(level.getLevel(), message);
        }
    }

    public static void setLogLevel(LogLevel level) {
        setLevel = level;
    }
}
enum LogLevel {
    DEBUG(Level.FINE), INFO(Level.INFO), ERROR(Level.SEVERE), NONE(Level.OFF);

    private final Level level;

    LogLevel(Level level) {
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }
}