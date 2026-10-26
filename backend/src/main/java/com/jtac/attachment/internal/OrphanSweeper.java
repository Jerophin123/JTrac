package com.jtac.attachment.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes stored files without an attachment row (left behind when issues or projects are
 * deleted, or after a failed upload). Files younger than one hour are never touched, so
 * in-flight uploads are safe.
 */
@Component
public class OrphanSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweeper.class);

    private final JdbcClient jdbc;
    private final AttachmentProperties properties;

    OrphanSweeper(JdbcClient jdbc, AttachmentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${jtac.attachments.sweep-cron:0 17 * * * *}")
    void scheduledSweep() {
        sweep(Duration.ofHours(1));
    }

    /** Returns the number of deleted files. */
    public int sweep(Duration minAge) {
        Path root = properties.directory().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Set<String> known = new HashSet<>(jdbc.sql("SELECT storage_key FROM attachment").query(String.class).list());
        Instant cutoff = Instant.now().minus(minAge);
        int deleted = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            List<Path> candidates = files.filter(Files::isRegularFile).toList();
            for (Path file : candidates) {
                String key = root.relativize(file).toString().replace('\\', '/');
                if (!known.contains(key) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Attachment sweep failed: {}", e.getClass().getSimpleName());
        }
        if (deleted > 0) {
            log.info("Attachment sweep removed {} orphaned file(s)", deleted);
        }
        return deleted;
    }
}
