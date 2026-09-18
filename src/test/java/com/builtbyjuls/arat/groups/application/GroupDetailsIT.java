package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.testing.AratAccountFixture;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GroupDetailsIT extends PostgreSqlIntegrationTest {

    private static final String CORRELATION_ID = "group-details-test-123";
    private static final UUID OWNER_ID = AratAccountFixture.OWNER.account().accountId();
    private static final UUID MEMBER_ID = AratAccountFixture.MEMBER.account().accountId();
    private static final UUID OUTSIDER_ID = AratAccountFixture.OUTSIDER.account().accountId();
    private static final UUID RANDOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000099");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private GroupCreationService groupCreationService;

    private MockMvc mockMvc;
    private UUID groupId;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account").update();
        for (var account : AratAccountFixture.values()) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                    .param("accountId", account.account().accountId())
                    .param("displayName", account.account().displayName())
                    .update();
        }
        groupId = groupCreationService.create(new CreateGroupCommand(
                OWNER_ID, "group-details-create", "Weekend badminton", "Saturday court planning", CORRELATION_ID)).groupId();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void organizerAndActiveMemberReceiveTheSamePrivateProjection() throws Exception {
        insertMembership(MEMBER_ID, "MEMBER", "ACTIVE", null);

        var ownerResponse = readAs(OWNER_ID).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.name").value("Weekend badminton"))
                .andExpect(jsonPath("$.description").value("Saturday court planning"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members").value(org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.members[0].accountId").value(OWNER_ID.toString()))
                .andExpect(jsonPath("$.members[0].displayName").value("Ari Organizer"))
                .andExpect(jsonPath("$.members[0].role").value("ORGANIZER"))
                .andExpect(jsonPath("$.members[1].accountId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var memberResponse = readAs(MEMBER_ID).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(memberResponse).isEqualTo(ownerResponse);
    }

    @Test
    void formerMembersOutsidersAndRandomIdsReceiveIndistinguishableNotFoundProblems() throws Exception {
        insertMembership(MEMBER_ID, "MEMBER", "REMOVED", "statement_timestamp()");
        insertMembership(OUTSIDER_ID, "MEMBER", "LEFT", "statement_timestamp()");

        var removedMember = readAs(MEMBER_ID).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var formerMember = readAs(OUTSIDER_ID).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var randomAccount = readAs(RANDOM_ID).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var randomGroup = mockMvc.perform(get("/api/v1/groups/{groupId}", UUID.randomUUID())
                        .with(authentication(authenticationFor(OWNER_ID)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(withoutInstance(formerMember)).isEqualTo(withoutInstance(removedMember));
        assertThat(withoutInstance(randomAccount)).isEqualTo(withoutInstance(removedMember));
        assertThat(withoutInstance(randomGroup)).isEqualTo(withoutInstance(removedMember));
        mockMvc.perform(get("/api/v1/groups/{groupId}", groupId)
                        .with(authentication(authenticationFor(MEMBER_ID)))
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Private resource not found"))
                .andExpect(jsonPath("$.detail").value("The requested private resource was not found."))
                .andExpect(jsonPath("$.groupId").doesNotExist());
    }

    @Test
    void publishesThePrivateGroupReadOperationInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}'].get.operationId").value("readGroup"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}'].get.responses['200'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/GroupDetailRepresentation"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}'].get.responses['404'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"));
    }

    private org.springframework.test.web.servlet.ResultActions readAs(UUID accountId) throws Exception {
        return mockMvc.perform(get("/api/v1/groups/{groupId}", groupId)
                .with(authentication(authenticationFor(accountId)))
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, Set.of());
    }

    private void insertMembership(UUID accountId, String role, String status, String endedAtExpression) {
        var endedAt = endedAtExpression == null ? "NULL" : endedAtExpression;
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at)
                        VALUES (:groupId, :accountId, :role, :status, statement_timestamp(), %s)
                        """.formatted(endedAt))
                .param("groupId", groupId)
                .param("accountId", accountId)
                .param("role", role)
                .param("status", status)
                .update();
    }

    private String withoutInstance(String problem) throws Exception {
        var json = new tools.jackson.databind.ObjectMapper().readTree(problem);
        ((tools.jackson.databind.node.ObjectNode) json).remove("instance");
        return json.toString();
    }
}
