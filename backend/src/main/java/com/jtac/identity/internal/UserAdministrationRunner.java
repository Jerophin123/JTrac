package com.jtac.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-shot administration command ({@code ./gradlew createUser} / {@code deleteUser}, or the
 * {@code grant-admin} / {@code revoke-admin} actions for server administration). The first
 * account created on an empty installation becomes a server administrator, like first-run setup.
 * Inputs come from environment variables so a password never appears in a command line
 * or shell history: {@code JTAC_USER_EMAIL}, {@code JTAC_USER_DISPLAY_NAME},
 * {@code JTAC_USER_PASSWORD}. It runs as {@code jtac_app} (DML only), like the application.
 */
@Component
@ConditionalOnProperty("jtac.admin.user-action")
class UserAdministrationRunner implements ApplicationRunner {

    private final UserAccountService accounts;
    private final String action;

    UserAdministrationRunner(UserAccountService accounts, @Value("${jtac.admin.user-action}") String action) {
        this.accounts = accounts;
        this.action = action;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = requiredEnv("JTAC_USER_EMAIL");
        switch (action) {
            case "create" -> {
                String name = requiredEnv("JTAC_USER_DISPLAY_NAME");
                String password = requiredEnv("JTAC_USER_PASSWORD");
                if (accounts.anyAccountExists()) {
                    System.out.println("Created user " + accounts.create(email, name, password, true));
                } else {
                    System.out.println("Created server administrator " + accounts.createServerAdministrator(email, name, password));
                }
            }
            case "delete" -> System.out.println(accounts.deleteByEmail(email) ? "Deleted user" : "No such user");
            case "grant-admin" -> System.out.println(accounts.setServerAdministrator(email, true)
                    ? "Granted server administration" : "No such user");
            case "revoke-admin" -> System.out.println(accounts.setServerAdministrator(email, false)
                    ? "Revoked server administration" : "No such user");
            default -> throw new IllegalArgumentException(
                    "Unknown user action '" + action + "' (use create, delete, grant-admin or revoke-admin)");
        }
    }

    private static String requiredEnv(String name) {
        String altName = name.startsWith("JTAC_") ? name.replace("JTAC_", "JTRAC_") : name.replace("JTRAC_", "JTAC_");
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(altName);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + name + " (or " + altName + ") is required");
        }
        return value;
    }
    private static String unusedOldRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + name + " is required");
        }
        return value;
    }
}
