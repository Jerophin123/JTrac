package com.jtac.field.internal;

import com.jtac.audit.AuditEntry;
import com.jtac.audit.AuditLog;
import com.jtac.field.FieldCatalog;
import com.jtac.field.FieldDefinition;
import com.jtac.field.FieldType;
import com.jtac.project.ProjectContext;
import com.jtac.project.ProjectPermission;
import com.jtac.shared.Ids;
import com.jtac.shared.web.ApiErrors;
import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
class FieldService implements FieldCatalog {

    static final int MAX_FIELDS_PER_PROJECT = 50;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{1,39}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");
    private static final TypeReference<List<FieldDefinition.Option>> OPTIONS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditLog audit;

    FieldService(JdbcClient jdbc, JsonMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    record OptionInput(String id, String label, String color) {
    }

    // --- FieldCatalog --------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<FieldDefinition> fields(UUID projectId) {
        return jdbc.sql("""
                SELECT id, key, name, type, options::text AS options, description, position,
                       archived_at IS NOT NULL AS archived, version
                  FROM field_definition WHERE project_id = :p ORDER BY position, lower(name)
                """)
                .param("p", projectId)
                .query((rs, n) -> new FieldDefinition(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"),
                        FieldType.valueOf(rs.getString("type")), json.readValue(rs.getString("options"), OPTIONS),
                        rs.getString("description"), rs.getInt("position"), rs.getBoolean("archived"), rs.getLong("version")))
                .list();
    }

    @Override
    public Optional<FieldDefinition> byKey(UUID projectId, String key) {
        return fields(projectId).stream().filter(f -> f.key().equals(key)).findFirst();
    }

    @Override
    public Object normalize(FieldDefinition field, Object raw, Predicate<UUID> userAllowed) {
        String path = "customValues." + field.key();
        if (raw == null || (raw instanceof String s && s.isBlank()) || (raw instanceof Collection<?> c && c.isEmpty())) {
            return null;
        }
        return switch (field.type()) {
            case TEXT -> text(path, raw, 500);
            case LONG_TEXT -> text(path, raw, 10_000);
            case URL -> {
                String value = text(path, raw, 2000);
                try {
                    URI uri = new URI(value);
                    if (!Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                        throw ApiErrors.invalid(path, "Enter an http(s) URL.");
                    }
                } catch (java.net.URISyntaxException e) {
                    throw ApiErrors.invalid(path, "Enter a valid URL.");
                }
                yield value;
            }
            case NUMBER -> {
                BigDecimal number;
                try {
                    number = raw instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(raw.toString().strip());
                } catch (NumberFormatException e) {
                    throw ApiErrors.invalid(path, "Enter a number.");
                }
                if (number.abs().compareTo(new BigDecimal("1e12")) > 0 || number.scale() > 6) {
                    throw ApiErrors.invalid(path, "The number is out of range.");
                }
                yield number.stripTrailingZeros();
            }
            case DATE -> {
                try {
                    yield LocalDate.parse(raw.toString().strip()).toString();
                } catch (DateTimeParseException e) {
                    throw ApiErrors.invalid(path, "Use a date in YYYY-MM-DD format.");
                }
            }
            case CHECKBOX -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                throw ApiErrors.invalid(path, "Use true or false.");
            }
            case SELECT -> {
                String option = raw.toString();
                if (!field.hasOption(option)) {
                    throw ApiErrors.invalid(path, "Choose one of the field's options.");
                }
                yield option;
            }
            case MULTI_SELECT -> {
                if (!(raw instanceof Collection<?> values)) {
                    throw ApiErrors.invalid(path, "Use a list of options.");
                }
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                for (Object value : values) {
                    if (value == null || !field.hasOption(value.toString())) {
                        throw ApiErrors.invalid(path, "Choose from the field's options.");
                    }
                    unique.add(value.toString());
                }
                yield List.copyOf(unique);
            }
            case USER -> {
                UUID user;
                try {
                    user = UUID.fromString(raw.toString());
                } catch (IllegalArgumentException e) {
                    throw ApiErrors.invalid(path, "Choose a person.");
                }
                if (!userAllowed.test(user)) {
                    throw ApiErrors.invalid(path, "This person cannot access the project.");
                }
                yield user.toString();
            }
        };
    }

    private static String text(String path, Object raw, int max) {
        if (!(raw instanceof String s)) {
            throw ApiErrors.invalid(path, "Enter text.");
        }
        String value = s.strip();
        if (value.length() > max) {
            throw ApiErrors.invalid(path, "At most " + max + " characters.");
        }
        return value;
    }

    // --- administration ------------------------------------------------------------------

    @Transactional
    FieldDefinition create(ProjectContext ctx, String key, String name, FieldType type, List<OptionInput> options,
            String description) {
        manage(ctx);
        if (fields(ctx.projectId()).size() >= MAX_FIELDS_PER_PROJECT) {
            throw ApiErrors.conflict("A project can have at most " + MAX_FIELDS_PER_PROJECT + " custom fields.");
        }
        String cleanName = requireName(name);
        String cleanKey = key == null || key.isBlank() ? keyFrom(cleanName) : key.strip();
        if (!KEY.matcher(cleanKey).matches()) {
            throw ApiErrors.invalid("key", "Use 2–40 lowercase letters, digits or '_', starting with a letter.");
        }
        if (byKey(ctx.projectId(), cleanKey).isPresent()) {
            throw ApiErrors.invalid("key", "Another field already uses this key.");
        }
        if (type == null) {
            throw ApiErrors.invalid("type", "Choose a field type.");
        }
        List<FieldDefinition.Option> cleanOptions = cleanOptions(type, options);
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO field_definition (id, organization_id, project_id, key, name, type, options, description, position)
                VALUES (:id, :org, :p, :key, :name, :type, CAST(:options AS jsonb), :description,
                        (SELECT coalesce(max(position) + 1, 0) FROM field_definition WHERE project_id = :p))
                """)
                .param("id", id).param("org", ctx.organizationId()).param("p", ctx.projectId()).param("key", cleanKey)
                .param("name", cleanName).param("type", type.name()).param("options", json.writeValueAsString(cleanOptions))
                .param("description", cleanDescription(description)).update();
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "field.created").target("field", id)
                .detail("key", cleanKey).detail("type", type.name()));
        return byKey(ctx.projectId(), cleanKey).orElseThrow();
    }

    @Transactional
    FieldDefinition update(ProjectContext ctx, UUID fieldId, String name, List<OptionInput> options, String description,
            Integer position, boolean archived, long expectedVersion) {
        manage(ctx);
        FieldDefinition field = fields(ctx.projectId()).stream().filter(f -> f.id().equals(fieldId)).findFirst()
                .orElseThrow(() -> ApiErrors.notFound("Field"));
        List<FieldDefinition.Option> cleanOptions = options == null ? field.options() : cleanOptions(field.type(), options);
        int updated = jdbc.sql("""
                UPDATE field_definition SET name = :name, options = CAST(:options AS jsonb), description = :description,
                       position = :position, archived_at = CASE WHEN :archived THEN coalesce(archived_at, now()) ELSE NULL END,
                       version = version + 1
                 WHERE id = :id AND project_id = :p AND version = :version
                """)
                .param("name", requireName(name)).param("options", json.writeValueAsString(cleanOptions))
                .param("description", cleanDescription(description))
                .param("position", position == null ? field.position() : position).param("archived", archived)
                .param("id", fieldId).param("p", ctx.projectId()).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("field changed");
        }
        audit.record(AuditEntry.of(ctx.organizationId(), ctx.actorId(), "field.updated").target("field", fieldId)
                .detail("archived", archived));
        return byKey(ctx.projectId(), field.key()).orElseThrow();
    }

    private List<FieldDefinition.Option> cleanOptions(FieldType type, List<OptionInput> options) {
        if (!type.hasOptions()) {
            return List.of();
        }
        if (options == null || options.isEmpty() || options.size() > 100) {
            throw ApiErrors.invalid("options", "Add 1 to 100 options.");
        }
        List<FieldDefinition.Option> result = new ArrayList<>();
        Set<String> labels = new java.util.HashSet<>();
        Set<String> ids = new java.util.HashSet<>();
        for (OptionInput option : options) {
            String label = option.label() == null ? "" : option.label().strip();
            if (label.isEmpty() || label.length() > 60) {
                throw ApiErrors.invalid("options", "Each option needs a label of 1 to 60 characters.");
            }
            if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                throw ApiErrors.invalid("options", "Option labels must be unique: " + label);
            }
            String id = option.id() == null || option.id().isBlank() ? Ids.newId().toString().substring(24) : option.id();
            if (!id.matches("^[a-z0-9-]{4,36}$") || !ids.add(id)) {
                throw ApiErrors.invalid("options", "Invalid option id.");
            }
            String color = option.color() == null ? "#6c645a" : option.color().toLowerCase(Locale.ROOT);
            if (!COLOR.matcher(color).matches()) {
                throw ApiErrors.invalid("options", "Use hex colors such as #b4532a.");
            }
            result.add(new FieldDefinition.Option(id, label, color));
        }
        return result;
    }

    private static void manage(ProjectContext ctx) {
        ctx.require(ProjectPermission.MANAGE_PROJECT);
        if (ctx.project().archived()) {
            throw ApiErrors.forbidden("This project is archived and read-only.");
        }
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 60) {
            throw ApiErrors.invalid("name", "Use 1 to 60 characters.");
        }
        return clean;
    }

    private static String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        if (description.length() > 500) {
            throw ApiErrors.invalid("description", "At most 500 characters.");
        }
        return description.strip();
    }

    static String keyFrom(String name) {
        String key = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) {
            key = "field_" + key;
        }
        return key.length() > 40 ? key.substring(0, 40) : key;
    }
}
