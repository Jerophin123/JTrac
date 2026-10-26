package com.jtac.integration.internal;

import com.jtac.identity.CurrentUser;
import com.jtac.issue.IssueFilter;
import com.jtac.organization.OrgContext;
import com.jtac.organization.OrganizationAccess;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class CsvController {

    private static final String ORG = ApiPaths.V1 + "/orgs/{org}";
    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;

    private final OrganizationAccess organizations;
    private final CsvService csv;

    CsvController(OrganizationAccess organizations, CsvService csv) {
        this.organizations = organizations;
        this.csv = csv;
    }

    record ExportRequest(IssueFilter filter) {
    }

    /** Streams matching issues as UTF-8 CSV (with BOM, so spreadsheet apps detect the encoding). */
    @PostMapping(ORG + "/issues/export")
    void export(CurrentUser user, @PathVariable String org, @RequestBody(required = false) ExportRequest body,
            HttpServletResponse response) throws IOException {
        OrgContext ctx = organizations.require(user, org);
        IssueFilter filter = body == null || body.filter() == null ? IssueFilter.empty() : body.filter();
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("jtac-" + ctx.slug() + "-issues-" + LocalDate.now(ZoneOffset.UTC) + ".csv").build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        writer.write('﻿');
        csv.export(ctx, filter, writer);
        writer.flush();
    }

    @PostMapping(path = ORG + "/projects/{key}/import", consumes = "multipart/form-data")
    CsvService.ImportResult importIssues(CurrentUser user, @PathVariable String org, @PathVariable String key,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
        OrgContext ctx = organizations.require(user, org);
        if (file.isEmpty()) {
            throw ApiErrors.invalid("file", "Choose a CSV file.");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw ApiErrors.invalid("file", "The file is larger than 2 MB.");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            return csv.importIssues(ctx, key, reader, dryRun);
        } catch (CharacterCodingException e) {
            throw ApiErrors.invalid("file", "The file must be UTF-8 encoded.");
        }
    }
}
