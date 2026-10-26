/**
 * Custom fields: per-project field definitions (type, options) and value validation.
 * Values are stored in the issue's {@code custom_values} JSONB keyed by field key.
 * Visibility/editability per status and role is a workflow concern (field rules).
 *
 * <p>Public API: {@link com.jtac.field.FieldCatalog}, {@link com.jtac.field.FieldDefinition},
 * {@link com.jtac.field.FieldType}.
 */
@ApplicationModule(displayName = "Custom fields")
package com.jtac.field;

import org.springframework.modulith.ApplicationModule;
