package com.jtac.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jtac.identity.CurrentUser;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.organization.OrgRole;
import com.jtac.shared.web.ApiErrors.Conflict;
import com.jtac.shared.web.ApiErrors.ValidationFailed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

class FirstRunSetupTests {

    @TempDir
    Path tempDir;

    private Path codeFile;
    private UserDirectory users;
    private OrganizationService organizations;
    private JdbcClient jdbc;
    private FirstRunSetup setup;

    @BeforeEach
    void setUp() {
        codeFile = tempDir.resolve("setup-code.txt");
        users = mock(UserDirectory.class);
        organizations = mock(OrganizationService.class);
        jdbc = mock(JdbcClient.class);

        // Mock advisory lock sql
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql("SELECT pg_advisory_xact_lock(:key)")).thenReturn(spec);
        when(spec.param(eq("key"), any())).thenReturn(spec);
        JdbcClient.ResultQuerySpec querySpec = mock(JdbcClient.ResultQuerySpec.class);
        when(spec.query()).thenReturn(querySpec);

        setup = new FirstRunSetup(users, organizations, jdbc, codeFile, "");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(codeFile);
    }

    @Test
    void setupNotRequiredWhenAccountsExist() {
        when(users.anyAccountExists()).thenReturn(true);
        assertThat(setup.required()).isFalse();
        assertThat(Files.exists(codeFile)).isFalse();
    }

    @Test
    void setupRequiredGeneratesCodeFileWhenNoAccounts() throws IOException {
        when(users.anyAccountExists()).thenReturn(false);
        assertThat(setup.required()).isTrue();
        assertThat(Files.exists(codeFile)).isTrue();
        String code = Files.readString(codeFile).strip();
        assertThat(code).hasSize(14); // 12 chars + 2 hyphens
    }

    @Test
    void completeFailsWhenWrongCode() {
        when(users.anyAccountExists()).thenReturn(false);
        setup.required(); // writes code

        assertThatThrownBy(() -> setup.complete("WRONG-CODE-1234", "Admin", "admin@test.com", "validPassword123!",
                "Acme", "acme", true, "127.0.0.1"))
                .isInstanceOf(ValidationFailed.class);
    }

    @Test
    void completeFailsWhenAccountsAlreadyExist() {
        when(users.anyAccountExists()).thenReturn(true);

        assertThatThrownBy(() -> setup.complete("SOME-CODE", "Admin", "admin@test.com", "validPassword123!",
                "Acme", "acme", true, "127.0.0.1"))
                .isInstanceOf(Conflict.class);
    }

    @Test
    void completeCreatesAdminAndOrgWithCorrectCode() throws IOException {
        when(users.anyAccountExists()).thenReturn(false);
        setup.required();
        String code = Files.readString(codeFile).strip();

        UUID adminId = UUID.randomUUID();
        when(users.createServerAdministrator("admin@test.com", "Admin User", "validPassword123!"))
                .thenReturn(adminId);
        when(users.find(adminId)).thenReturn(Optional.of(
                new UserSummary(adminId, "admin@test.com", "Admin User", "admin", true)));
        when(organizations.create(any(CurrentUser.class), eq("Acme Corp"), eq("acme")))
                .thenReturn(new OrganizationService.OrgSummary(UUID.randomUUID(), "acme", "Acme Corp", OrgRole.OWNER, null, null));

        // Accept with dashes or spaces or lowercase
        String resultSlug = setup.complete(code.toLowerCase().replace("-", " "), "Admin User", "admin@test.com",
                "validPassword123!", "Acme Corp", "acme", true, "127.0.0.1");

        assertThat(resultSlug).isEqualTo("acme");
        verify(users).createServerAdministrator("admin@test.com", "Admin User", "validPassword123!");
        verify(users).recordConsent(adminId);
    }

    @Test
    void completeRequiresAcceptedTerms() throws IOException {
        when(users.anyAccountExists()).thenReturn(false);
        setup.required();
        String code = Files.readString(codeFile).strip();

        assertThatThrownBy(() -> setup.complete(code, "Admin User", "admin@test.com", "validPassword123!",
                "Acme Corp", "acme", false, "127.0.0.1"))
                .isInstanceOf(ValidationFailed.class);
        verify(users, never()).createServerAdministrator(any(), any(), any());
    }
}

