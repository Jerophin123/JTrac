package com.jtrac.serverconfig;

/**
 * Everything an operator configures for one JTRAC installation, except secrets (see
 * {@link Secrets}). Immutable; change a section with the {@code with*} methods.
 */
public record ServerConfig(Http http, Database database, SmtpSettings smtp, Security security, Storage storage, Legal legal) {

    /** Where the web application listens and how users reach it. */
    public record Http(String bindAddress, int port, String publicUrl, boolean behindProxy) {

        /** Session cookies are marked Secure whenever users reach JTRAC over HTTPS. */
        public boolean secureCookies() {
            return publicUrl != null && publicUrl.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
        }
    }

    /**
     * The PostgreSQL database. In {@link DatabaseMode#MANAGED} mode JTRAC runs its own
     * PostgreSQL bound to the loopback interface; in {@link DatabaseMode#EXTERNAL} mode the
     * operator provides one.
     */
    public record Database(DatabaseMode mode, String host, int port, String name, String appUser, String ownerUser,
            String sslMode) {

        public String jdbcUrl() {
            String url = "jdbc:postgresql://" + hostForUrl(host) + ":" + port + "/" + name;
            return sslMode == null || sslMode.isBlank() ? url : url + "?sslmode=" + sslMode;
        }

        private static String hostForUrl(String host) {
            return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        }
    }

    /** Account and session policy. */
    public record Security(RegistrationMode registrationMode, int sessionTimeoutHours) {
    }

    /** Directories for data that must survive upgrades. */
    public record Storage(String attachmentsDir, String backupDir, String logDir) {
    }

    /** Operator details shown in the Terms of Service, Privacy Notice and grievance page. */
    public record Legal(String operatorName, String operatorAddress, String contactEmail, String grievanceOfficerName,
            String grievanceOfficerEmail, String jurisdiction) {

        public static final Legal EMPTY = new Legal("", "", "", "", "", "");
    }

    public enum RegistrationMode { INVITE_ONLY, OPEN }

    public ServerConfig withHttp(Http value) {
        return new ServerConfig(value, database, smtp, security, storage, legal);
    }

    public ServerConfig withDatabase(Database value) {
        return new ServerConfig(http, value, smtp, security, storage, legal);
    }

    public ServerConfig withSmtp(SmtpSettings value) {
        return new ServerConfig(http, database, value, security, storage, legal);
    }

    public ServerConfig withSecurity(Security value) {
        return new ServerConfig(http, database, smtp, value, storage, legal);
    }

    public ServerConfig withStorage(Storage value) {
        return new ServerConfig(http, database, smtp, security, value, legal);
    }

    public ServerConfig withLegal(Legal value) {
        return new ServerConfig(http, database, smtp, security, storage, value);
    }
}
