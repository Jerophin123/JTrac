package com.jtac.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jtrac.serverconfig.ConfigStore;
import com.jtrac.serverconfig.DatabaseMode;
import com.jtrac.serverconfig.Secrets;
import com.jtrac.serverconfig.ServerConfig;
import com.jtrac.serverconfig.SmtpSettings;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The files written by {@code jtrac setup} / {@code jtrac configure sso}, read the way the
 * server imports them: the registration resolves its client secret from secrets.properties and
 * the SSO policy binds to {@link SsoProperties}. (Discovery is covered by the setup check.)
 */
class SsoInstalledConfigurationTests {

    @TempDir
    Path dir;

    @Test
    void serverReadsTheSsoSettingsSetupWrites() throws IOException {
        ConfigStore store = new ConfigStore(dir);
        ServerConfig.Sso sso = new ServerConfig.Sso(true, "entra", "Microsoft Entra ID",
                "https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/v2.0", "app-client-id", true,
                "example.com, Example.org", true);
        store.saveConfig(new ServerConfig(
                new ServerConfig.Http("127.0.0.1", 8080, "https://jtrac.example.com", true),
                new ServerConfig.Database(DatabaseMode.MANAGED, "127.0.0.1", 54329, "jtrac", "jtrac_app", "jtrac_owner", "disable"),
                SmtpSettings.disabled(), new ServerConfig.Security(ServerConfig.RegistrationMode.INVITE_ONLY, 8),
                new ServerConfig.Storage(dir.resolve("a").toString(), dir.resolve("b").toString(), dir.resolve("l").toString()),
                ServerConfig.Legal.EMPTY, sso));
        store.saveSecrets(new Secrets("db", "", "key", "s3cr=t:with#chars"));

        StandardEnvironment environment = new StandardEnvironment();
        PropertiesPropertySourceLoader loader = new PropertiesPropertySourceLoader();
        for (Path file : new Path[] {store.settingsFile(), store.secretsFile()}) {
            loader.load(file.getFileName().toString(), new FileSystemResource(file))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        }
        Binder binder = Binder.get(environment);

        OAuth2ClientProperties client = binder.bind("spring.security.oauth2.client", Bindable.of(OAuth2ClientProperties.class))
                .get();
        OAuth2ClientProperties.Registration registration = client.getRegistration().get("entra");
        assertThat(registration.getClientId()).isEqualTo("app-client-id");
        assertThat(registration.getClientSecret()).isEqualTo("s3cr=t:with#chars");
        assertThat(registration.getClientName()).isEqualTo("Microsoft Entra ID");
        assertThat(registration.getAuthorizationGrantType()).isEqualTo("authorization_code");
        assertThat(registration.getScope()).containsExactlyInAnyOrder("openid", "email", "profile");
        assertThat(client.getProvider().get("entra").getIssuerUri()).isEqualTo(sso.issuerUri());

        SsoProperties policy = binder.bind("jtac.sso", Bindable.of(SsoProperties.class)).get();
        assertThat(policy.autoProvision()).isTrue();
        assertThat(policy.trustUnverifiedEmail()).isTrue();
        assertThat(policy.allowedDomains()).containsExactly("example.com", "example.org");
    }
}
