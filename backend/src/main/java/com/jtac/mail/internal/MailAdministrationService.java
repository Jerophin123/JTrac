package com.jtac.mail.internal;

import com.jtac.mail.MailAdministration;
import com.jtrac.serverconfig.ConfigMapper;
import com.jtrac.serverconfig.ConfigStore;
import com.jtrac.serverconfig.ConfigValidator;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.Redaction;
import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.SmtpTestResult;
import com.jtrac.serverconfig.SmtpTester;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Saves SMTP settings to the same files the setup wizard writes (ordinary settings in
 * jtrac.properties, the password in the owner-only secrets.properties), applies them without a
 * restart, and runs test emails through the shared {@link SmtpTester}.
 */
@Service
class MailAdministrationService implements MailAdministration {

    /** Property sources that take precedence over the configuration files. */
    private static final List<String> OVERRIDING_SOURCES = List.of("commandLineArgs", "systemProperties", "systemEnvironment");

    private final MailRuntime runtime;
    private final JdbcClient jdbc;
    private final ConfigurableEnvironment environment;
    private final ConfigStore store;

    MailAdministrationService(MailRuntime runtime, JdbcClient jdbc, ConfigurableEnvironment environment,
            @Value("${jtrac.config-dir:./config}") Path configDirectory) {
        this.runtime = runtime;
        this.jdbc = jdbc;
        this.environment = environment;
        this.store = new ConfigStore(configDirectory);
    }

    @Override
    public MailStatus status() {
        MailRuntime.Current current = runtime.current();
        Outbox outbox = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'SENT' AND sent_at > now() - interval '24 hours') AS sent,
                       (SELECT last_error FROM email_outbox WHERE last_error IS NOT NULL
                         ORDER BY next_attempt_at DESC LIMIT 1) AS last_error
                  FROM email_outbox
                """)
                .query((rs, n) -> new Outbox(rs.getLong("pending"), rs.getLong("failed"), rs.getLong("sent"),
                        Redaction.scrub(rs.getString("last_error"), current.password())))
                .single();
        return new MailStatus(current.smtp(), current.password() != null && !current.password().isEmpty(), overrides(),
                store.directory().toString(), outbox);
    }

    @Override
    public List<Problem> update(SmtpSettings settings, String password) {
        String effective = password == null ? runtime.current().password() : password;
        List<Problem> problems = ConfigValidator.validateSmtp(settings, effective);
        if (ConfigValidator.hasErrors(problems)) {
            return problems;
        }
        try {
            store.mergeSettings(ConfigMapper.toSmtpProperties(settings));
            if (password != null) {
                store.mergeSecrets(Map.of(ConfigMapper.MAIL_PASSWORD, password));
            }
        } catch (IOException e) {
            throw new IllegalStateException("The email settings could not be saved to " + store.directory() + ": " + e.getMessage(), e);
        }
        runtime.apply(settings, effective);
        return problems;
    }

    @Override
    public SmtpTestResult test(SmtpSettings settings, String password, String recipient) {
        return SmtpTester.test(settings, password == null ? runtime.current().password() : password, recipient);
    }

    private List<String> overrides() {
        List<String> keys = new ArrayList<>(ConfigMapper.SMTP_KEYS);
        keys.add(ConfigMapper.MAIL_PASSWORD);
        List<String> overridden = new ArrayList<>();
        for (String key : keys) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                // Boot's "configurationProperties" source is a view over all the others.
                if (!source.getName().equals("configurationProperties") && source.containsProperty(key)) {
                    if (OVERRIDING_SOURCES.contains(source.getName())) {
                        overridden.add(key);
                    }
                    break;
                }
            }
        }
        return overridden;
    }
}
