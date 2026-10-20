package net.flameslight.rwstructuresawareness.logger;

import net.flameslight.rwstructuresawareness.RWStructuresAwareness;
import net.flameslight.rwstructuresawareness.config.CommonConfig;
import org.slf4j.Logger;

import java.text.MessageFormat;

public class ModLogger {
    private static final Logger LOGGER = RWStructuresAwareness.LOGGER;

    /**
     * Logs at info level.
     *
     * @param message an SLF4J message, {} placeholders filled from {@code args}
     * @param args    the placeholder values, a trailing Throwable is logged with its stack trace
     */
    public static void info(String message, Object ... args) {
        LOGGER.info(buildMessageLog(message), args);
    }

    /**
     * Logs at info level with a [DEBUG] tag, only while the debugMod option is on.
     *
     * @param message an SLF4J message, {} placeholders filled from {@code args}
     * @param args    the placeholder values
     */
    public static void debug(String message, Object ... args) {
        if(CommonConfig.isDebugMod())
            LOGGER.info(buildMessageLog("[DEBUG]: " + message), args);
    }

    /**
     * Logs at warn level.
     *
     * @param message an SLF4J message, {} placeholders filled from {@code args}
     * @param args    the placeholder values, a trailing Throwable is logged with its stack trace
     */
    public static void warn(String message, Object ... args) {
        LOGGER.warn(buildMessageLog(message), args);
    }

    /**
     * Logs at error level.
     *
     * @param message an SLF4J message, {} placeholders filled from {@code args}
     * @param args    the placeholder values, a trailing Throwable is logged with its stack trace
     */
    public static void error(String message, Object ... args) {
        LOGGER.error(buildMessageLog(message), args);
    }

    /**
     * Logs at error level with a stack trace.
     *
     * @param message the message, no placeholders
     * @param error   the cause, logged with its stack trace
     */
    public static void error(String message, Throwable error) {
        LOGGER.error(buildMessageLog(message), error);
    }

    /**
     * Prefixes a message with the mod's log tag.
     *
     * @param message the message
     * @return "RWSA-logger: " followed by the message
     */
    private static String buildMessageLog(String message) {
        return MessageFormat.format("{0}: {1}", "RWSA-logger", message);
    }
}
