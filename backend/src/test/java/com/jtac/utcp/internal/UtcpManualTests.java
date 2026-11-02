package com.jtac.utcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;

@DatabaseIntegrationTest
class UtcpManualTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Test
    void discoveryManualConformsToUtcpSpecification() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/utcp")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        Map<?, ?> body = json.readValue(content, Map.class);
        assertThat(body).isNotNull();

        // 1. Pinned UTCP version and manual version
        assertThat(body.get("utcp_version")).isEqualTo(UtcpManual.SPEC_VERSION).isEqualTo("1.0.1");
        assertThat(body.get("manual_version")).isEqualTo(UtcpManual.MANUAL_VERSION).isEqualTo("1.0.0");
        assertThat(result.getResponse().getHeader("X-Provider")).isEqualTo("JTRAC");

        // 2. Tools catalogue
        assertThat(body.get("tools")).isInstanceOf(List.class);
        List<?> tools = (List<?>) body.get("tools");
        assertThat(tools).hasSize(24);

        for (Object toolObj : tools) {
            assertThat(toolObj).isInstanceOf(Map.class);
            Map<?, ?> tool = (Map<?, ?>) toolObj;
            assertThat(tool.get("name")).isNotNull();
            assertThat(tool.get("description")).isNotNull();
            assertThat(tool.get("inputs")).isInstanceOf(Map.class);
            assertThat(tool.get("outputs")).isInstanceOf(Map.class);
            assertThat(tool.get("tags")).isInstanceOf(List.class);
            List<?> tags = (List<?>) tool.get("tags");
            assertThat(tags.contains("jtrac")).isTrue();

            // Verify call template
            assertThat(tool.get("tool_call_template")).isInstanceOf(Map.class);
            Map<?, ?> template = (Map<?, ?>) tool.get("tool_call_template");
            assertThat(template.get("name")).isEqualTo("jtrac");
            assertThat(template.get("call_template_type")).isEqualTo("http");
            assertThat(template.get("http_method")).isEqualTo("POST");
            assertThat(template.get("url")).isNotNull();

            // Verify auth config
            assertThat(template.get("auth")).isInstanceOf(Map.class);
            Map<?, ?> auth = (Map<?, ?>) template.get("auth");
            assertThat(auth.get("auth_type")).isEqualTo("api_key");
            assertThat(auth.get("location")).isEqualTo("header");
            assertThat(auth.get("var_name")).isEqualTo("Authorization");
            assertThat(auth.get("api_key")).isEqualTo("Bearer ${JTRAC_API_TOKEN}");
        }
    }

    @Test
    void manualAliasReturnsSameManual() throws Exception {
        MvcResult manualRes = mvc.perform(MockMvcRequestBuilders.get("/api/v1/utcp"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult aliasRes = mvc.perform(MockMvcRequestBuilders.get("/api/v1/utcp/manual"))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> manual = json.readValue(manualRes.getResponse().getContentAsString(), Map.class);
        Map<?, ?> alias = json.readValue(aliasRes.getResponse().getContentAsString(), Map.class);
        assertThat(manual).isEqualTo(alias);
    }

    @Test
    void toolsEndpointListsAllTools() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/utcp/tools"))
                .andExpect(status().isOk())
                .andReturn();

        List<?> list = json.readValue(res.getResponse().getContentAsString(), List.class);
        assertThat(list).hasSize(24);
    }

    @Test
    void toolByNameReturnsIndividualDefinition() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.get("/api/v1/utcp/tools/search_issues"))
                .andExpect(status().isOk())
                .andReturn();

        String content = res.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(content, "$.name")).isEqualTo("search_issues");
        assertThat((String) JsonPath.read(content, "$.tool_call_template.call_template_type")).isEqualTo("http");
    }
}

