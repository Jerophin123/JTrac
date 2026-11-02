package com.jtac.utcp.internal;

import java.util.List;
import java.util.Map;

/**
 * Universal Tool Calling Protocol (UTCP) Manual and data models adhering to the official
 * UTCP 1.0.1 specification (https://utcp.io / https://github.com/universal-tool-calling-protocol/utcp-specification).
 */
final class UtcpManual {

    public static final String SPEC_VERSION = "1.0.1";
    public static final String MANUAL_VERSION = "1.0.0";

    record Manual(
            String utcp_version,
            String manual_version,
            List<Tool> tools
    ) {}

    record Tool(
            String name,
            String description,
            Map<String, Object> inputs,
            Map<String, Object> outputs,
            List<String> tags,
            ToolCallTemplate tool_call_template
    ) {}

    record ToolCallTemplate(
            String name,
            String call_template_type,
            String url,
            String http_method,
            Map<String, String> headers,
            AuthConfig auth
    ) {}

    record AuthConfig(
            String auth_type,
            String api_key,
            String var_name,
            String location
    ) {}

    private UtcpManual() {}
}

