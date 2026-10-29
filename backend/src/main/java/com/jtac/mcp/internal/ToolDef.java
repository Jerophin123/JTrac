package com.jtac.mcp.internal;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An MCP tool: its contract (name, description, input JSON Schema, behaviour hints) and the
 * scope a connection needs to see and call it ({@code null}: any authenticated connection).
 */
record ToolDef(String name, String title, String description, Scope scope, Kind kind, Map<String, Object> inputSchema,
        Handler handler) {

    enum Kind {
        /** Reads only. */
        READ,
        /** Adds information (comment, report); repeating it adds again unless a requestId is given. */
        ADDITIVE,
        /** Changes existing data (fields, status, links). */
        CHANGE
    }

    @FunctionalInterface
    interface Handler {
        /**
         * @return a result object serialized as JSON (structured content plus its text form), or a
         *         {@link Output} carrying additional content such as an image
         */
        Object handle(AgentRequest request, Args args);
    }

    /** A result with extra content (images, embedded files) besides its JSON summary. */
    record Output(Object summary, List<McpSchema.Content> content) {
    }

    /** Implemented by beans that contribute tools. */
    interface Provider {
        List<ToolDef> tools();
    }

    McpSchema.Tool toMcp() {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(kind == Kind.READ)
                .destructiveHint(kind == Kind.CHANGE)
                .idempotentHint(kind == Kind.READ)
                .openWorldHint(false)
                .build();
        return McpSchema.Tool.builder(name, inputSchema).title(title).description(description).annotations(annotations).build();
    }

    // --- JSON Schema builder (draft 2020-12 subset used by MCP clients) ---------------------------

    static final class Schema {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        static Schema object() {
            return new Schema();
        }

        Schema str(String name, String description, int maxLength, boolean isRequired) {
            return prop(name, Map.of("type", "string", "description", description, "maxLength", maxLength), isRequired);
        }

        Schema strEnum(String name, String description, List<String> values, boolean isRequired) {
            return prop(name, Map.of("type", "string", "description", description, "enum", values), isRequired);
        }

        Schema integer(String name, String description, int min, int max) {
            return prop(name, Map.of("type", "integer", "description", description, "minimum", min, "maximum", max), false);
        }

        Schema bool(String name, String description) {
            return prop(name, Map.of("type", "boolean", "description", description), false);
        }

        Schema strings(String name, String description, int maxItems, int maxLength) {
            return prop(name, Map.of("type", "array", "description", description, "maxItems", maxItems,
                    "items", Map.of("type", "string", "maxLength", maxLength)), false);
        }

        Schema enums(String name, String description, List<String> values) {
            return prop(name, Map.of("type", "array", "description", description, "maxItems", values.size(),
                    "items", Map.of("type", "string", "enum", values)), false);
        }

        /** A nullable string: {@code null} clears the field. */
        Schema nullableStr(String name, String description, int maxLength) {
            return prop(name, Map.of("type", List.of("string", "null"), "description", description, "maxLength", maxLength), false);
        }

        Schema raw(String name, Map<String, Object> schema, boolean isRequired) {
            return prop(name, schema, isRequired);
        }

        private Schema prop(String name, Map<String, Object> schema, boolean isRequired) {
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
                schema.put("required", List.copyOf(required));
            }
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
