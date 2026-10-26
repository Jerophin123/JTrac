package com.jtac.field;

import java.util.List;
import java.util.UUID;

/**
 * A custom field of a project. {@code key} is immutable and used as the storage key and in
 * workflow field rules ({@code cf:<key>}).
 */
public record FieldDefinition(UUID id, String key, String name, FieldType type, List<Option> options,
        String description, int position, boolean archived, long version) {

    public record Option(String id, String label, String color) {
    }

    /** Field identifier used in workflow field rules and search. */
    public String ruleKey() {
        return "cf:" + key;
    }

    public boolean hasOption(String optionId) {
        return options.stream().anyMatch(o -> o.id().equals(optionId));
    }
}
