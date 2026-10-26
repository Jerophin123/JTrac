package com.jtac.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Graceful stop on every operating system: the {@code jtrac stop} command writes a
 * stop-request file and the server shuts down cleanly (finishing requests, closing the
 * database pool). Signals can't do this on Windows, where terminating a process is abrupt.
 * Active only when the launcher sets {@code jtrac.stop-file}.
 */
@Component
@ConditionalOnProperty("jtrac.stop-file")
class StopRequestWatcher {

    private static final Logger log = LoggerFactory.getLogger(StopRequestWatcher.class);

    private final ConfigurableApplicationContext context;
    private final Path stopFile;

    StopRequestWatcher(ConfigurableApplicationContext context, org.springframework.core.env.Environment environment) {
        this.context = context;
        this.stopFile = Path.of(environment.getRequiredProperty("jtrac.stop-file"));
    }

    @EventListener(ApplicationReadyEvent.class)
    void watch() throws java.io.IOException {
        // A stale request from an earlier run must not stop this one.
        Files.deleteIfExists(stopFile);
        Thread.ofPlatform().daemon().name("jtrac-stop-watcher").start(() -> {
            while (context.isActive()) {
                if (Files.exists(stopFile)) {
                    log.info("Stop requested by the jtrac command; shutting down gracefully.");
                    try {
                        Files.deleteIfExists(stopFile);
                    } catch (java.io.IOException e) {
                        log.warn("Could not remove the stop-request file: {}", e.getMessage());
                    }
                    System.exit(SpringApplication.exit(context, () -> 0));
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }
}
