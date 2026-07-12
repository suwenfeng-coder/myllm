package com.example.myllm.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Class<?> sourceType) {
        logger = (Logger) LoggerFactory.getLogger(sourceType);
        previousLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
    }

    public static LogCapture forClass(Class<?> sourceType) {
        return new LogCapture(sourceType);
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    public List<String> messages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    public ILoggingEvent eventStartingWith(String prefix) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
