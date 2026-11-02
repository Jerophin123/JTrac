package com.jtac.utcp.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of an internal UTCP tool with schema and execution handler.
 */
record UtcpToolDef(
        String name,
        String description,
        String category,
        String scope,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Handler handler
) {

    @FunctionalInterface
    interface Handler {
        Object handle(UtcpContext context, Map<String, Object> arguments) throws Exception;
    }

    static final class Schema {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        static Schema object() {
            return new Schema();
        }

        Schema str(String name, String description, int maxLength, boolean isRequired) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "string");
            prop.put("description", description);
            if (maxLength > 0) {
                prop.put("maxLength", maxLength);
            }
            properties.put(name, prop);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        Schema strEnum(String name, String description, List<String> values, boolean isRequired) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "string");
            prop.put("description", description);
            prop.put("enum", values);
            properties.put(name, prop);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        Schema integer(String name, String description, int min, int max, boolean isRequired) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "integer");
            prop.put("description", description);
            prop.put("minimum", min);
            prop.put("maximum", max);
            properties.put(name, prop);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        Schema bool(String name, String description, boolean isRequired) {
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "boolean");
            prop.put("description", description);
            properties.put(name, prop);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        Schema strings(String name, String description, int maxItems, int maxLength) {
            Map<String, Object> itemSchema = new LinkedHashMap<>();
            itemSchema.put("type", "string");
            if (maxLength > 0) {
                itemSchema.put("maxLength", maxLength);
            }
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("description", description);
            if (maxItems > 0) {
                prop.put("maxItems", maxItems);
            }
            prop.put("items", itemSchema);
            properties.put(name, prop);
            return this;
        }

        Schema enums(String name, String description, List<String> values) {
            Map<String, Object> itemSchema = new LinkedHashMap<>();
            itemSchema.put("type", "string");
            itemSchema.put("enum", values);
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", "array");
            prop.put("description", description);
            prop.put("items", itemSchema);
            properties.put(name, prop);
            return this;
        }

        Schema raw(String name, Map<String, Object> schema, boolean isRequired) {
            properties.put(name, schema);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}

