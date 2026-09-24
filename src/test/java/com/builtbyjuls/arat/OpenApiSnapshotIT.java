package com.builtbyjuls.arat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@ActiveProfiles({"test", "local"})
class OpenApiSnapshotIT extends PostgreSqlIntegrationTest {

    private static final Path SNAPSHOT = Path.of("ui", "openapi", "arat-v1.json");
    private static final String UPDATE_PROPERTY = "arat.openapi.snapshot.update";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void executableLocalOpenApiMatchesTheReviewedSnapshot() throws Exception {
        var executableOpenApi = executableOpenApi();
        var normalized = normalize(executableOpenApi);
        var root = objectMapper.readTree(normalized);

        assertThat(root.path("paths").path("/api/v1/dev/whoami").path("get").isObject()).isTrue();

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, normalized, StandardCharsets.UTF_8);
        }

        assertSnapshotMatches(SNAPSHOT, executableOpenApi);
    }

    @Test
    void driftCheckRejectsADeliberatelyChangedTemporarySnapshot(@TempDir Path temporaryDirectory) throws Exception {
        var executableOpenApi = executableOpenApi();
        var changed = (ObjectNode) objectMapper.readTree(executableOpenApi);
        ((ObjectNode) changed.path("info")).put("title", "Changed API");
        var changedSnapshot = temporaryDirectory.resolve("arat-v1.json");
        Files.writeString(changedSnapshot, normalize(changed.toString()), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> assertSnapshotMatches(changedSnapshot, executableOpenApi))
                .isInstanceOf(AssertionError.class);
    }

    private String executableOpenApi() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void assertSnapshotMatches(Path snapshot, String executableOpenApi) throws IOException {
        assertThat(Files.readString(snapshot, StandardCharsets.UTF_8))
                .isEqualTo(normalize(executableOpenApi));
    }

    private String normalize(String json) {
        try {
            var lineFeedIndenter = new DefaultIndenter("  ", "\n");
            var prettyPrinter = new DefaultPrettyPrinter()
                    .withObjectIndenter(lineFeedIndenter);
            return objectMapper.writer().with(prettyPrinter)
                    .writeValueAsString(canonicalize(objectMapper.readTree(json))) + "\n";
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("OpenAPI document cannot be normalized", exception);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(field -> fields.put(field.getKey(), canonicalize(field.getValue())));
            var canonicalObject = objectMapper.createObjectNode();
            fields.forEach(canonicalObject::set);
            return canonicalObject;
        }
        if (value.isArray()) {
            var canonicalArray = objectMapper.createArrayNode();
            value.forEach(element -> canonicalArray.add(canonicalize(element)));
            return canonicalArray;
        }
        return value.deepCopy();
    }
}
