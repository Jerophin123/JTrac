package com.jtac.field;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public interface FieldCatalog {

    /** All fields of a project, including archived ones, ordered by position. */
    List<FieldDefinition> fields(UUID projectId);

    Optional<FieldDefinition> byKey(UUID projectId, String key);

    /**
     * Validates and normalizes a value for storage: String, BigDecimal, Boolean, LocalDate
     * text or List of option ids. {@code null} (or empty) means "no value".
     *
     * @param userAllowed for USER fields: whether the referenced user may be chosen
     * @throws com.jtac.shared.web.ApiErrors.ValidationFailed with field {@code customValues.<key>}
     */
    Object normalize(FieldDefinition field, Object raw, Predicate<UUID> userAllowed);

    /** Whether a stored value counts as empty for "required" checks. */
    static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof List<?> list && list.isEmpty());
    }
}
