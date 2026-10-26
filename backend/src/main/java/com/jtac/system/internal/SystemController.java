package com.jtac.system.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.mail.MailAdministration;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import com.jtrac.serverconfig.Problem;
import com.jtrac.serverconfig.SmtpSettings;
import com.jtrac.serverconfig.SmtpTestResult;
import com.jtrac.serverconfig.TlsMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Server settings for server administrators (authorization in {@link SystemService}). */
@RestController
@RequestMapping(ApiPaths.V1 + "/system")
class SystemController {

    private final SystemService system;
    private final ObjectProvider<BuildProperties> build;
    private final String publicUrl;

    SystemController(SystemService system, ObjectProvider<BuildProperties> build, @Value("${jtac.public-url}") String publicUrl) {
        this.system = system;
        this.build = build;
        this.publicUrl = publicUrl;
    }

    @GetMapping("/info")
    ResponseEntity<ServerInfo> info(CurrentUser user) {
        system.requireServerAdmin(user);
        BuildProperties props = build.getIfAvailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ServerInfo(
                props == null ? "development" : props.getVersion(), publicUrl, Runtime.version().toString()));
    }

    @GetMapping("/mail")
    ResponseEntity<MailView> mail(CurrentUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MailView.of(system.mailStatus(user)));
    }

    @PutMapping("/mail")
    ResponseEntity<MailUpdateResult> updateMail(CurrentUser user, @Valid @RequestBody MailUpdate body) {
        List<Problem> problems = system.updateMail(user, body.settings().toSettings(), body.password());
        List<Problem> errors = problems.stream().filter(Problem::isError).toList();
        if (!errors.isEmpty()) {
            throw new ApiErrors.ValidationFailed(errors.stream().map(p -> new ApiErrors.FieldError(p.field(), p.message())).toList());
        }
        return ResponseEntity.ok(new MailUpdateResult(MailView.of(system.mailStatus(user)),
                problems.stream().map(p -> new Warning(p.field(), p.message())).toList()));
    }

    @PostMapping("/mail/test")
    SmtpTestResult testMail(CurrentUser user, @Valid @RequestBody MailTest body) {
        return system.testMail(user, body.settings().toSettings(), body.password(), body.recipient().strip());
    }

    record ServerInfo(String version, String publicUrl, String javaVersion) {
    }

    /** SMTP settings as the API sends and receives them; the password is never returned. */
    record SmtpBody(boolean enabled, @Size(max = 253) String host, @Min(1) @Max(65535) int port, boolean auth,
            @Size(max = 320) String username, @NotNull TlsMode tls, @Size(max = 320) String fromAddress,
            @Size(max = 100) String fromName, @Size(max = 320) String replyTo, @Min(1) @Max(300) int timeoutSeconds,
            @Min(1) @Max(50) int maxAttempts, @Size(max = 1024) String trustedCertificates) {

        SmtpSettings toSettings() {
            return new SmtpSettings(enabled, strip(host), port, auth, strip(username), tls, strip(fromAddress), strip(fromName),
                    strip(replyTo), timeoutSeconds, maxAttempts, strip(trustedCertificates));
        }

        static SmtpBody of(SmtpSettings s) {
            return new SmtpBody(s.enabled(), s.host(), s.port(), s.auth(), s.username(), s.tls(), s.fromAddress(), s.fromName(),
                    s.replyTo(), s.timeoutSeconds(), s.maxAttempts(), s.trustedCertificates());
        }

        private static String strip(String value) {
            return value == null ? "" : value.strip();
        }
    }

    record MailView(SmtpBody settings, boolean passwordSet, List<String> overriddenBy, String configDirectory,
            MailAdministration.Outbox outbox) {

        static MailView of(MailAdministration.MailStatus status) {
            return new MailView(SmtpBody.of(status.settings()), status.passwordSet(), status.overriddenBy(),
                    status.configDirectory(), status.outbox());
        }
    }

    /** @param password new password; omit (null) to keep the stored one */
    record MailUpdate(@NotNull @Valid SmtpBody settings, @Size(max = 1024) String password) {
        @Override
        public String toString() {
            return "MailUpdate[settings=" + settings + ", password=****]";
        }
    }

    record MailUpdateResult(MailView mail, List<Warning> warnings) {
    }

    record Warning(String field, String message) {
    }

    /** @param password password to test with; omit (null) to use the stored one */
    record MailTest(@NotNull @Valid SmtpBody settings, @Size(max = 1024) String password,
            @NotBlank @Size(max = 320) String recipient) {
        @Override
        public String toString() {
            return "MailTest[settings=" + settings + ", recipient=" + recipient + ", password=****]";
        }
    }
}
