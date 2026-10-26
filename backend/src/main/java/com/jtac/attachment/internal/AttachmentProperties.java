package com.jtac.attachment.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param directory   where files are stored (must be persistent and backed up)
 * @param maxFileSize per-file limit (also enforce {@code spring.servlet.multipart.max-file-size})
 * @param maxPerIssue maximum number of attachments per issue
 */
@ConfigurationProperties("jtac.attachments")
record AttachmentProperties(
        @DefaultValue("./data/attachments") Path directory,
        @DefaultValue("25MB") DataSize maxFileSize,
        @DefaultValue("100") int maxPerIssue) {
}
