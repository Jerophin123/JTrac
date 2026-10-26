package com.jtac.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.jtac.DatabaseIntegrationTest;
import com.jtac.identity.UserDirectory;
import com.jtac.support.Fixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Workflow transitions per role, workflow versioning with migration, and the issue lifecycle. */
@DatabaseIntegrationTest
class WorkflowAndIssueLifecycleTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private UserDirectory users;

    @Autowired
    private JsonMapper json;

    private Fixtures fx;
    private Fixtures.User owner;
    private Fixtures.User dev;
    private Fixtures.Org org;

    @BeforeEach
    void setUp() throws Exception {
        fx = new Fixtures(mvc, jdbc, users);
        owner = fx.user("Owner");
        dev = fx.user("Dev");
        org = fx.org(owner);
        fx.member(org, dev, "MEMBER");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void structuredWorkflowRestrictsTransitionsByRoleAndRequiresFields() throws Exception {
        fx.project(owner, org, "FLOW", "STRUCTURED");
        Map<String, String> statuses = statuses("FLOW");
        String key = fx.issue(dev, org, "FLOW", "{\"title\":\"Feature\"}");

        // Not a transition of the workflow.
        transition(dev, key, statuses.get("Done")).andExpect(status().isForbidden());
        // "In Progress" requires an assignee.
        transition(dev, key, statuses.get("In Progress")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("assigneeId")));
        dev.client().patch(org.path("/issues/" + key), "{\"changes\":{\"statusId\":\"" + statuses.get("In Progress")
                        + "\",\"assigneeId\":\"" + dev.id() + "\"},\"version\":" + version(dev, key) + "}")
                .andExpect(status().isOk());
        transition(dev, key, statuses.get("In Review")).andExpect(status().isOk());
        // Only the project administrator role may approve.
        dev.client().get(org.path("/issues/" + key)).andExpect(jsonPath("$.transitions[*].name").value(
                not(hasItem("Approve"))));
        transition(dev, key, statuses.get("Done")).andExpect(status().isForbidden());
        owner.client().get(org.path("/issues/" + key)).andExpect(jsonPath("$.transitions[*].name").value(hasItem("Approve")));
        transition(owner, key, statuses.get("Done")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status.category").value("DONE"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        transition(dev, key, statuses.get("To Do")).andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    void concurrentEditsAreDetectedWithOptimisticLocking() throws Exception {
        fx.project(owner, org, "LOCK", "KANBAN");
        String key = fx.issue(owner, org, "LOCK", "{\"title\":\"Shared\"}");
        long version = version(owner, key);
        owner.client().patch(org.path("/issues/" + key), "{\"changes\":{\"title\":\"First\"},\"version\":" + version + "}")
                .andExpect(status().isOk());
        dev.client().patch(org.path("/issues/" + key), "{\"changes\":{\"title\":\"Second\"},\"version\":" + version + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:jtac:problem:version-conflict"));
        owner.client().get(org.path("/issues/" + key)).andExpect(jsonPath("$.title").value("First"));
    }

    @Test
    void subtasksLinksAndDependencyCycles() throws Exception {
        fx.project(owner, org, "TREE", "KANBAN");
        String catalog = owner.client().get(org.path("/projects/TREE/catalog")).andReturn().getResponse().getContentAsString();
        String subtaskType = JsonPath.<List<String>>read(catalog, "$.issueTypes[?(@.subtask==true)].id").getFirst();
        String parent = fx.issue(owner, org, "TREE", "{\"title\":\"Parent\"}");
        owner.client().post(org.path("/projects/TREE/issues"), "{\"title\":\"Orphan\",\"typeId\":\"" + subtaskType + "\"}")
                .andExpect(status().isBadRequest());
        String child = fx.issue(owner, org, "TREE", "{\"title\":\"Child\",\"typeId\":\"" + subtaskType
                + "\",\"parentKey\":\"" + parent + "\"}");
        owner.client().get(org.path("/issues/" + parent)).andExpect(jsonPath("$.subtasks[0].key").value(child));
        owner.client().get(org.path("/issues/" + child)).andExpect(jsonPath("$.parent.key").value(parent));

        String a = fx.issue(owner, org, "TREE", "{\"title\":\"A\"}");
        String b = fx.issue(owner, org, "TREE", "{\"title\":\"B\"}");
        String c = fx.issue(owner, org, "TREE", "{\"title\":\"C\"}");
        owner.client().post(org.path("/issues/" + a + "/links"), "{\"targetKey\":\"" + b + "\",\"type\":\"BLOCKS\"}")
                .andExpect(status().isCreated());
        owner.client().post(org.path("/issues/" + b + "/links"), "{\"targetKey\":\"" + c + "\",\"type\":\"BLOCKS\"}")
                .andExpect(status().isCreated());
        owner.client().post(org.path("/issues/" + c + "/links"), "{\"targetKey\":\"" + a + "\",\"type\":\"BLOCKS\"}")
                .andExpect(status().isBadRequest());
        owner.client().post(org.path("/issues/" + a + "/links"), "{\"targetKey\":\"" + b + "\",\"type\":\"BLOCKS\"}")
                .andExpect(status().isConflict());
        owner.client().get(org.path("/issues/" + b)).andExpect(jsonPath("$.links.length()").value(2));

        // Deleting the parent deletes its sub-tasks.
        owner.client().delete(org.path("/issues/" + parent)).andExpect(status().isNoContent());
        owner.client().get(org.path("/issues/" + child)).andExpect(status().isNotFound());
    }

    @Test
    void publishingAWorkflowMigratesIssuesExplicitly() throws Exception {
        fx.project(owner, org, "MIG", "KANBAN");
        Map<String, String> statuses = statuses("MIG");
        String key = fx.issue(owner, org, "MIG", "{\"title\":\"Waiting for review\"}");
        transition(owner, key, statuses.get("In Review")).andExpect(status().isOk());

        String overview = owner.client().get(org.path("/projects/MIG/workflow")).andReturn().getResponse().getContentAsString();
        Map<String, Object> definition = new LinkedHashMap<>(JsonPath.read(overview, "$.published.definition"));
        String review = statuses.get("In Review");
        definition.put("statuses", ((List<?>) definition.get("statuses")).stream().filter(s -> !s.equals(review)).toList());
        definition.put("transitions", JsonPath.<List<Map<String, Object>>>read(overview, "$.published.definition.transitions")
                .stream().filter(t -> !review.equals(t.get("to"))).toList());
        owner.client().put(org.path("/projects/MIG/workflow/draft"), json.writeValueAsString(definition)).andExpect(status().isOk());
        owner.client().get(org.path("/projects/MIG/workflow/draft/impact"))
                .andExpect(jsonPath("$.removedStatuses[0].issueCount").value(1));
        // Developers cannot publish; publishing without a mapping for affected issues fails.
        dev.client().post(org.path("/projects/MIG/workflow/draft/publish"), "{}").andExpect(status().isForbidden());
        owner.client().post(org.path("/projects/MIG/workflow/draft/publish"), "{}").andExpect(status().isBadRequest());
        owner.client().post(org.path("/projects/MIG/workflow/draft/publish"),
                        "{\"mapping\":{\"" + review + "\":\"" + statuses.get("In Progress") + "\"}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionNo").value(2));
        owner.client().get(org.path("/issues/" + key)).andExpect(jsonPath("$.status.name").value("In Progress"));
        owner.client().get(org.path("/issues/" + key + "/activity")).andExpect(jsonPath("$[*].field").value(hasItem("status")));
        owner.client().get(org.path("/projects/MIG/workflow")).andExpect(jsonPath("$.published.versionNo").value(2))
                .andExpect(jsonPath("$.draft").doesNotExist());
    }

    @Test
    void boardOrderBulkChangesAndSearchPagination() throws Exception {
        fx.project(owner, org, "BRD", "KANBAN");
        String first = fx.issue(owner, org, "BRD", "{\"title\":\"First\"}");
        String second = fx.issue(owner, org, "BRD", "{\"title\":\"Second\"}");
        String board = owner.client().post(org.path("/projects/BRD/board"), "{}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(board, "$.columns[?(@.status.name=='Todo')].issues[*].key")).containsExactly(first, second);

        // Move "second" above "first" (keyboard or drag and drop use the same endpoint).
        String firstId = JsonPath.read(owner.client().get(org.path("/issues/" + first)).andReturn().getResponse().getContentAsString(), "$.id");
        owner.client().post(org.path("/issues/" + second + "/move"), "{\"beforeId\":\"" + firstId + "\",\"version\":"
                + version(owner, second) + "}").andExpect(status().isOk());
        board = owner.client().post(org.path("/projects/BRD/board"), "{}").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(board, "$.columns[?(@.status.name=='Todo')].issues[*].key")).containsExactly(second, first);

        owner.client().post(org.path("/issues/bulk"), "{\"keys\":[\"" + first + "\",\"" + second + "\",\"BRD-999\"],"
                        + "\"changes\":{\"priority\":\"URGENT\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ok").value(true)).andExpect(jsonPath("$[1].ok").value(true))
                .andExpect(jsonPath("$[2].ok").value(false));
        owner.client().post(org.path("/issues/search"), "{\"filter\":{\"projectKeys\":[\"BRD\"],\"priorities\":[\"URGENT\"]},\"size\":1}")
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        // Developers cannot bulk-delete (no DELETE_ISSUES): each item reports the failure.
        dev.client().post(org.path("/issues/bulk"), "{\"keys\":[\"" + first + "\"],\"delete\":true}")
                .andExpect(jsonPath("$[0].ok").value(false));
        owner.client().get(org.path("/issues/" + first)).andExpect(status().isOk());
    }

    @Test
    void everyEventListenerCompletesForIssueEvents() throws Exception {
        fx.project(owner, org, "EVT", "KANBAN");
        String key = fx.issue(owner, org, "EVT", "{\"title\":\"Event source\",\"assigneeId\":\"" + dev.id() + "\"}");
        owner.client().post(org.path("/issues/" + key + "/comments"), "{\"body\":\"hello @" + dev.handle() + "\"}")
                .andExpect(status().isCreated());
        // Realtime, notification and webhook listeners each record a publication; all must complete.
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            List<Map<String, Object>> publications = jdbc.sql(
                            "SELECT listener_id, completion_date FROM event_publication WHERE serialized_event LIKE :key")
                    .param("key", "%\"" + key + "\"%").query().listOfRows();
            assertThat(publications).isNotEmpty();
            assertThat(publications).allSatisfy(p -> assertThat(p.get("completion_date")).as(p.get("listener_id").toString()).isNotNull());
        });
    }

    private ResultActions transition(Fixtures.User user, String key, String statusId)
            throws Exception {
        return user.client().patch(org.path("/issues/" + key), "{\"changes\":{\"statusId\":\"" + statusId
                + "\"},\"version\":" + version(user, key) + "}");
    }

    private long version(Fixtures.User user, String key) throws Exception {
        return ((Number) JsonPath.read(user.client().get(org.path("/issues/" + key)).andReturn().getResponse()
                .getContentAsString(), "$.version")).longValue();
    }

    private Map<String, String> statuses(String project) throws Exception {
        String overview = owner.client().get(org.path("/projects/" + project + "/workflow")).andReturn().getResponse()
                .getContentAsString();
        List<Map<String, Object>> list = JsonPath.read(overview, "$.statuses");
        return list.stream().collect(Collectors.toMap(s -> (String) s.get("name"), s -> (String) s.get("id")));
    }
}
