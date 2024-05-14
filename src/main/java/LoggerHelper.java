import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class LoggerHelper {
    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(LoggerHelper.class.getName());
    private static LogLevel setLevel = LogLevel.INFO;

    static {
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new SimpleFormatter());
        logger.addHandler(consoleHandler);
        logger.setUseParentHandlers(false); // Disable parent handlers to prevent duplicate logging
    }

    public static void log(LogLevel level, String message) {
        if (setLevel != LogLevel.NONE && level.getLevel().intValue() >= setLevel.getLevel().intValue()) {
            logger.log(level.getLevel(), message);
        }
    }

    public static void setLogLevel(LogLevel level) {
        setLevel = level;
    }
    private static class SimpleFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getMessage() + System.lineSeparator();
        }
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