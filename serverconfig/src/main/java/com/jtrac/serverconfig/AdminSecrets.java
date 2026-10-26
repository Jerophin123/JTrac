package com.jtrac.serverconfig;

/**
 * Database administration secrets used only by the launcher (migrations, provisioning,
 * backups). The running web application never receives them.
 *
 * @param ownerPassword      password of the schema owner role, which runs migrations
 * @param superuserPassword  password of the managed cluster's superuser (managed mode only)
 */
public record AdminSecrets(String ownerPassword, String superuserPassword) {

    public static final AdminSecrets EMPTY = new AdminSecrets("", "");

    @Override
    public String toString() {
        return "AdminSecrets[****]";
    }
}
