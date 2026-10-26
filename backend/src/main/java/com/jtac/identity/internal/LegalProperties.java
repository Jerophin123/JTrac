package com.jtac.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who operates this JTRAC installation, as shown in the Terms of Service, the Privacy
 * Notice and the grievance page. Blank values are shown as clearly marked placeholders.
 *
 * @param version                 version of the terms in force; changing it asks every user to accept again
 * @param effectiveDate           date the current terms took effect (ISO yyyy-mm-dd)
 * @param operatorName            legal name of the organization operating the installation (the Data Fiduciary)
 * @param operatorAddress         its postal address
 * @param contactEmail            general privacy contact
 * @param grievanceOfficerName    the person who handles grievances (DPDP Act, section 13)
 * @param grievanceOfficerEmail   where grievances and erasure requests are sent
 * @param jurisdiction            courts with jurisdiction over disputes, e.g. "Chennai, Tamil Nadu"
 */
@ConfigurationProperties("jtac.legal")
record LegalProperties(
        @DefaultValue("2026-10-26") String version,
        @DefaultValue("2026-10-26") String effectiveDate,
        @DefaultValue("") String operatorName,
        @DefaultValue("") String operatorAddress,
        @DefaultValue("") String contactEmail,
        @DefaultValue("") String grievanceOfficerName,
        @DefaultValue("") String grievanceOfficerEmail,
        @DefaultValue("") String jurisdiction) {
}
