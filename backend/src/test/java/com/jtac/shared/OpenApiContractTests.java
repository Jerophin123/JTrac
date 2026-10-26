package com.jtac.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.jtac.DatabaseIntegrationTest;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards against drift between the contract ({@code api/openapi.yaml}) and the
 * implementation: every documented /api/v1 operation is implemented, and every
 * implemented /api/v1 operation is documented.
 */
@DatabaseIntegrationTest
class OpenApiContractTests {

    /** Operations implemented by Spring Security filters rather than controllers. */
    private static final Set<String> FILTER_OPERATIONS = Set.of("POST /api/v1/auth/logout");
    private static final Path SPEC = Path.of("..", "api", "openapi.yaml");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void specificationAndImplementationDescribeTheSameApi() throws IOException {
        assertThat(documentedOperations()).isEqualTo(implementedOperations());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documentedOperations() throws IOException {
        Set<String> operations = new TreeSet<>();
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            Map<String, Object> spec = new Yaml().load(reader);
            assertThat(spec.get("openapi")).asString().startsWith("3.1");
            Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
            paths.forEach((path, item) -> {
                if (path.startsWith("/api/v1/")) {
                    item.keySet().stream()
                            .filter(Set.of("get", "post", "put", "patch", "delete")::contains)
                            .forEach(method -> operations.add(method.toUpperCase() + " " + path));
                }
            });
        }
        return operations;
    }

    private Set<String> implementedOperations() {
        Set<String> operations = new TreeSet<>(FILTER_OPERATIONS);
        handlerMapping.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
                .filter(pattern -> pattern.startsWith("/api/v1/"))
                .forEach(pattern -> info.getMethodsCondition().getMethods()
                        .forEach(httpMethod -> operations.add(httpMethod.name() + " " + pattern))));
        return operations;
    }
}
