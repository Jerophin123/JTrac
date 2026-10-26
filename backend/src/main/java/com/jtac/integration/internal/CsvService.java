package com.jtac.integration.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.identity.UserDirectory;
import com.jtac.identity.UserSummary;
import com.jtac.issue.IssueCommands;
import com.jtac.issue.IssueFilter;
import com.jtac.issue.IssueHandle;
import com.jtac.issue.IssueSearch;
import com.jtac.issue.IssueSummary;
import com.jtac.issue.NewIssue;
import com.jtac.issue.Priority;
import com.jtac.organization.OrgContext;
import com.jtac.project.ProjectAccess;
import com.jtac.project.ProjectCatalog;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.web.ApiErrors;
import com.jtac.shared.web.ApiProblemException;
import com.jtac.shared.web.PageResponse;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CSV export of search results and all-or-nothing CSV import of new issues into a project.
 *
 * <p>Export contains only what the caller may see (hidden fields are blank) and neutralizes
 * spreadsheet formulas: cells starting with {@code = + - @}, tab or carriage return are
 * prefixed with an apostrophe.
 *
 * <p>Import creates issues in the workflow's initial status with the same validation and
 * field rules as the API. Every row is checked first and all problems are reported; if any
 * row fails (including during creation) nothing is imported. A dry run validates and creates
 * inside a transaction that is always rolled back.
 */
@Service
class CsvService {

    static final int MAX_EXPORT_ROWS = 10_000;
    static final int MAX_IMPORT_ROWS = 1_000;
    private static final List<String> SYSTEM_COLUMNS = List.of("key", "title", "type", "status", "priority", "assignee",
            "reporter", "labels", "milestone", "parent", "startDate", "dueDate", "estimate", "created", "updated", "resolved");
    private static final Set<String> IMPORT_COLUMNS = Set.of("title", "description", "type", "priority", "assignee",
            "labels", "milestone", "parent", "startdate", "duedate", "estimate");

    private final IssueSearch search;
    private final IssueCommands commands;
    private final ProjectAccess projects;
    private final ProjectCatalog catalog;
    private final FieldCatalog fields;
    private final UserDirectory users;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    CsvService(IssueSearch search, IssueCommands commands, ProjectAccess projects, ProjectCatalog catalog,
            FieldCatalog fields, UserDirectory users, AuditLog audit, TransactionTemplate tx) {
        this.search = search;
        this.commands = commands;
        this.projects = projects;
        this.catalog = catalog;
        this.fields = fields;
        this.users = users;
        this.audit = audit;
        this.tx = tx;
    }

    // --- export ----------------------------------------------------------------------------

    /** Writes matching issues as CSV; returns the number of rows written. */
    int export(OrgContext ctx, IssueFilter filter, Writer out) throws IOException {
        List<IssueSummary> rows = new ArrayList<>();
        int page = 0;
        while (rows.size() < MAX_EXPORT_ROWS) {
            PageResponse<IssueSummary> result = search.search(ctx, filter, page++, PageResponse.MAX_SIZE);
            rows.addAll(result.items());
            if (result.items().isEmpty() || rows.size() >= result.total()) {
                break;
            }
        }
        if (rows.size() > MAX_EXPORT_ROWS) {
            rows = rows.subList(0, MAX_EXPORT_ROWS);
        }
        // Custom field columns: union over the exported projects, keyed by field key.
        Map<String, FieldDefinition> customColumns = new LinkedHashMap<>();
        Map<UUID, Map<String, FieldDefinition>> fieldsByProject = new HashMap<>();
        for (UUID projectId : rows.stream().map(IssueSummary::projectId).distinct().toList()) {
            Map<String, FieldDefinition> byKey = new LinkedHashMap<>();
            for (FieldDefinition def : fields.fields(projectId)) {
                byKey.put(def.key(), def);
                customColumns.putIfAbsent(def.key(), def);
            }
            fieldsByProject.put(projectId, byKey);
        }
        Map<UUID, UserSummary> people = users.findAll(rows.stream()
                .flatMap(r -> r.customValues() == null ? java.util.stream.Stream.empty() : r.customValues().values().stream())
                .filter(v -> v instanceof String s && s.length() == 36)
                .map(v -> {
                    try {
                        return UUID.fromString((String) v);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .distinct().toList());

        List<String> header = new ArrayList<>(SYSTEM_COLUMNS);
        customColumns.keySet().forEach(k -> header.add("cf:" + k));
        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.RFC4180.builder().setHeader(header.toArray(String[]::new)).get())) {
            for (IssueSummary s : rows) {
                List<String> cells = new ArrayList<>();
                cells.add(s.key());
                cells.add(s.title());
                cells.add(s.type() == null ? "" : s.type().name());
                cells.add(s.status() == null ? "" : s.status().name());
                cells.add(s.priority() == null ? "" : s.priority().name());
                cells.add(s.assignee() == null ? "" : s.assignee().handle());
                cells.add(s.reporter() == null ? "" : s.reporter().handle());
                cells.add(s.labels() == null ? "" : String.join("; ", s.labels().stream().map(IssueSummary.LabelRef::name).toList()));
                cells.add(s.milestone() == null ? "" : s.milestone().name());
                cells.add(s.parentKey());
                cells.add(s.startDate() == null ? "" : s.startDate().toString());
                cells.add(s.dueDate() == null ? "" : s.dueDate().toString());
                cells.add(s.estimate() == null ? "" : s.estimate().toPlainString());
                cells.add(s.createdAt().toString());
                cells.add(s.updatedAt().toString());
                cells.add(s.resolvedAt() == null ? "" : s.resolvedAt().toString());
                Map<String, FieldDefinition> projectFields = fieldsByProject.getOrDefault(s.projectId(), Map.of());
                for (String key : customColumns.keySet()) {
                    FieldDefinition def = projectFields.get(key);
                    Object value = s.customValues() == null ? null : s.customValues().get(key);
                    cells.add(def == null ? "" : display(def, value, people));
                }
                printer.printRecord(cells.stream().map(CsvService::neutralize).toList());
            }
        }
        return rows.size();
    }

    private static String display(FieldDefinition def, Object value, Map<UUID, UserSummary> people) {
        if (value == null) {
            return "";
        }
        return switch (def.type()) {
            case SELECT -> optionLabel(def, value.toString());
            case MULTI_SELECT -> value instanceof Collection<?> list
                    ? String.join("; ", list.stream().map(v -> optionLabel(def, v.toString())).toList()) : "";
            case USER -> {
                try {
                    UserSummary user = people.get(UUID.fromString(value.toString()));
                    yield user == null ? "" : user.handle();
                } catch (IllegalArgumentException e) {
                    yield "";
                }
            }
            case NUMBER -> value instanceof BigDecimal d ? d.toPlainString() : value.toString();
            default -> value.toString();
        };
    }

    private static String optionLabel(FieldDefinition def, String id) {
        return def.options().stream().filter(o -> o.id().equals(id)).map(FieldDefinition.Option::label).findFirst().orElse("");
    }

    /** OWASP CSV injection mitigation. */
    static String neutralize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    // --- import ----------------------------------------------------------------------------

    record RowError(int row, String column, String message) {
    }

    record ImportResult(boolean dryRun, int rows, List<String> createdKeys, List<RowError> errors, List<String> ignoredColumns) {
    }

    ImportResult importIssues(OrgContext org, String projectKey, Reader input, boolean dryRun) throws IOException {
        ProjectContext ctx = projects.require(org, projectKey).require(ProjectPermission.CREATE_ISSUES);
        List<CSVRecord> records;
        List<String> header;
        try (CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).get().parse(input)) {
            header = parser.getHeaderNames().stream().map(h -> h.replace("﻿", "").strip()).toList();
            records = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (records.size() >= MAX_IMPORT_ROWS) {
                    throw ApiErrors.invalid("file", "At most " + MAX_IMPORT_ROWS + " rows can be imported at once.");
                }
                records.add(record);
            }
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            throw ApiErrors.invalid("file", "The file is not valid CSV: " + e.getMessage());
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i).toLowerCase(Locale.ROOT), i);
        }
        if (!columns.containsKey("title")) {
            throw ApiErrors.invalid("file", "The file needs a 'title' column.");
        }
        Mapping mapping = new Mapping(ctx);
        List<String> ignored = new ArrayList<>();
        Map<String, FieldDefinition> customColumns = new LinkedHashMap<>();
        for (String original : header) {
            String column = original.toLowerCase(Locale.ROOT);
            if (IMPORT_COLUMNS.contains(column)) {
                continue;
            }
            Optional<FieldDefinition> def = mapping.field(original);
            if (def.isPresent()) {
                customColumns.put(original, def.get());
            } else {
                ignored.add(original);
            }
        }

        List<RowError> errors = new ArrayList<>();
        List<NewIssue> issues = new ArrayList<>();
        for (CSVRecord record : records) {
            int row = (int) record.getRecordNumber() + 1; // 1-based line numbers including the header
            Row r = new Row(record, header);
            int before = errors.size();
            NewIssue issue = mapping.toIssue(r, customColumns, row, errors);
            if (errors.size() == before) {
                issues.add(issue);
            }
        }
        if (!errors.isEmpty()) {
            return new ImportResult(dryRun, records.size(), List.of(), errors, ignored);
        }
        List<String> created = new ArrayList<>();
        List<RowError> createErrors = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < issues.size(); i++) {
                try {
                    IssueHandle handle = commands.create(ctx, issues.get(i));
                    created.add(handle.key());
                } catch (ApiErrors.ValidationFailed e) {
                    int row = (int) records.get(i).getRecordNumber() + 1;
                    e.errors().forEach(fe -> createErrors.add(new RowError(row, fe.field(), fe.message())));
                    status.setRollbackOnly();
                    return;
                } catch (ApiProblemException e) {
                    createErrors.add(new RowError((int) records.get(i).getRecordNumber() + 1, null, e.getMessage()));
                    status.setRollbackOnly();
                    return;
                }
            }
            if (dryRun) {
                status.setRollbackOnly();
            } else {
                audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "issues.imported")
                        .target("project", ctx.projectId()).detail("count", created.size()));
            }
        });
        if (!createErrors.isEmpty()) {
            return new ImportResult(dryRun, records.size(), List.of(), createErrors, ignored);
        }
        return new ImportResult(dryRun, records.size(), created, List.of(), ignored);
    }

    /** One CSV record with case-insensitive column access. */
    private record Row(CSVRecord record, List<String> header) {
        String get(String column) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).equalsIgnoreCase(column) && i < record.size()) {
                    String value = record.get(i);
                    return value == null || value.isBlank() ? null : value.strip();
                }
            }
            return null;
        }

        String getExact(String column) {
            int index = header.indexOf(column);
            if (index < 0 || index >= record.size()) {
                return null;
            }
            String value = record.get(index);
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    /** Name-to-id resolution for one project, loaded once per import. */
    private final class Mapping {
        private final Map<String, UUID> types = new HashMap<>();
        private final Map<String, UUID> labels = new HashMap<>();
        private final Map<String, UUID> milestones = new HashMap<>();
        private final List<FieldDefinition> fieldDefs;
        private final Map<String, UUID> people = new HashMap<>();

        Mapping(ProjectContext ctx) {
            catalog.issueTypes(ctx.projectId()).stream().filter(t -> !t.archived())
                    .forEach(t -> types.putIfAbsent(t.name().toLowerCase(Locale.ROOT), t.id()));
            catalog.labels(ctx.projectId()).forEach(l -> labels.putIfAbsent(l.name().toLowerCase(Locale.ROOT), l.id()));
            catalog.milestones(ctx.projectId()).forEach(m -> milestones.putIfAbsent(m.name().toLowerCase(Locale.ROOT), m.id()));
            fieldDefs = fields.fields(ctx.projectId()).stream().filter(f -> !f.archived()).toList();
        }

        Optional<FieldDefinition> field(String column) {
            String key = column.startsWith("cf:") ? column.substring(3) : column;
            return fieldDefs.stream()
                    .filter(f -> f.key().equalsIgnoreCase(key) || f.name().equalsIgnoreCase(key))
                    .findFirst();
        }

        UUID person(String reference) {
            String ref = reference.startsWith("@") ? reference.substring(1) : reference;
            return people.computeIfAbsent(ref.toLowerCase(Locale.ROOT), r -> {
                if (r.contains("@")) {
                    return users.findByEmail(r).filter(UserSummary::active).map(UserSummary::id).orElse(null);
                }
                UserSummary user = users.findByHandles(List.of(r)).get(r);
                return user != null && user.active() ? user.id() : null;
            });
        }

        NewIssue toIssue(Row r, Map<String, FieldDefinition> customColumns, int row, List<RowError> errors) {
            String title = r.get("title");
            if (title == null) {
                errors.add(new RowError(row, "title", "A title is required."));
            }
            UUID typeId = null;
            String type = r.get("type");
            if (type != null) {
                typeId = types.get(type.toLowerCase(Locale.ROOT));
                if (typeId == null) {
                    errors.add(new RowError(row, "type", "Unknown issue type '" + type + "'."));
                }
            }
            Priority priority = null;
            String p = r.get("priority");
            if (p != null) {
                try {
                    priority = Priority.valueOf(p.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add(new RowError(row, "priority", "Unknown priority '" + p + "'."));
                }
            }
            UUID assignee = null;
            String a = r.get("assignee");
            if (a != null) {
                assignee = person(a);
                if (assignee == null) {
                    errors.add(new RowError(row, "assignee", "No active user '" + a + "'."));
                }
            }
            List<UUID> labelIds = new ArrayList<>();
            String l = r.get("labels");
            if (l != null) {
                for (String name : l.split("[;,]")) {
                    if (name.isBlank()) {
                        continue;
                    }
                    UUID id = labels.get(name.strip().toLowerCase(Locale.ROOT));
                    if (id == null) {
                        errors.add(new RowError(row, "labels", "Unknown label '" + name.strip() + "'."));
                    } else if (!labelIds.contains(id)) {
                        labelIds.add(id);
                    }
                }
            }
            UUID milestoneId = null;
            String m = r.get("milestone");
            if (m != null) {
                milestoneId = milestones.get(m.toLowerCase(Locale.ROOT));
                if (milestoneId == null) {
                    errors.add(new RowError(row, "milestone", "Unknown milestone '" + m + "'."));
                }
            }
            LocalDate start = date(r.get("startDate"), "startDate", row, errors);
            LocalDate due = date(r.get("dueDate"), "dueDate", row, errors);
            BigDecimal estimate = null;
            String e = r.get("estimate");
            if (e != null) {
                try {
                    estimate = new BigDecimal(e);
                } catch (NumberFormatException ex) {
                    errors.add(new RowError(row, "estimate", "Enter a number."));
                }
            }
            Map<String, Object> custom = new LinkedHashMap<>();
            for (Map.Entry<String, FieldDefinition> column : customColumns.entrySet()) {
                String raw = r.getExact(column.getKey());
                if (raw == null) {
                    continue;
                }
                FieldDefinition def = column.getValue();
                Object value = customValue(def, raw, row, column.getKey(), errors);
                if (value != null) {
                    custom.put(def.key(), value);
                }
            }
            return new NewIssue(typeId, title, r.get("description"), priority, assignee, r.get("parent"), milestoneId,
                    start, due, estimate, labelIds, custom);
        }

        private Object customValue(FieldDefinition def, String raw, int row, String column, List<RowError> errors) {
            return switch (def.type()) {
                case SELECT -> {
                    String id = option(def, raw);
                    if (id == null) {
                        errors.add(new RowError(row, column, "Unknown option '" + raw + "'."));
                    }
                    yield id;
                }
                case MULTI_SELECT -> {
                    List<String> ids = new ArrayList<>();
                    for (String part : raw.split(";")) {
                        if (part.isBlank()) {
                            continue;
                        }
                        String id = option(def, part.strip());
                        if (id == null) {
                            errors.add(new RowError(row, column, "Unknown option '" + part.strip() + "'."));
                        } else {
                            ids.add(id);
                        }
                    }
                    yield ids;
                }
                case CHECKBOX -> switch (raw.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "y", "1", "x" -> Boolean.TRUE;
                    case "false", "no", "n", "0" -> Boolean.FALSE;
                    default -> {
                        errors.add(new RowError(row, column, "Use true or false."));
                        yield null;
                    }
                };
                case USER -> {
                    UUID id = person(raw);
                    if (id == null) {
                        errors.add(new RowError(row, column, "No active user '" + raw + "'."));
                    }
                    yield id == null ? null : id.toString();
                }
                case NUMBER, TEXT, LONG_TEXT, URL, DATE -> raw;
            };
        }

        private static String option(FieldDefinition def, String labelOrId) {
            return def.options().stream()
                    .filter(o -> o.label().equalsIgnoreCase(labelOrId) || o.id().equals(labelOrId))
                    .map(FieldDefinition.Option::id).findFirst().orElse(null);
        }

        private static LocalDate date(String raw, String column, int row, List<RowError> errors) {
            if (raw == null) {
                return null;
            }
            try {
                return LocalDate.parse(raw);
            } catch (DateTimeParseException e) {
                errors.add(new RowError(row, column, "Use a date in YYYY-MM-DD format."));
                return null;
            }
        }
    }
}
