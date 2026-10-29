package com.shtong.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** End-to-end API behaviour in the in-memory (development) profile. */
@SpringBootTest(properties = {
        "shtong.dev-mode=true",
        "SHTONG_BOOTSTRAP_ADMIN_ONE_LOGIN=flow.admin.one",
        "SHTONG_BOOTSTRAP_ADMIN_ONE_PASSWORD=FlowAdminOne2026",
        "SHTONG_BOOTSTRAP_ADMIN_TWO_LOGIN=flow.admin.two",
        "SHTONG_BOOTSTRAP_ADMIN_TWO_PASSWORD=FlowAdminTwo2026",
        "shtong.medical-insurance.sync-delay-ms=3600000",
        "shtong.maintenance.prune-delay-ms=3600000"
})
@AutoConfigureMockMvc
class GatewayFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountAccessService service;

    @Test
    void administratorsSignInWithPasswordOnly() throws Exception {
        JsonNode login = login("flow.admin.one", "FlowAdminOne2026");
        assertThat(login.path("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(login.path("challengeId").isNull()).isTrue();
        assertThat(login.at("/session/context/workspace").asText()).isEqualTo("SYSTEM_ADMIN");
        assertThat(login.at("/session/context/assuranceLevel").asText()).isEqualTo("AAL1");
        String token = token(login);
        assertThat(call(get("/v1/system/accounts"), token).getResponse().getStatus()).isEqualTo(200);
        JsonNode bootstrap = body(call(get("/v1/session/bootstrap"), token));
        assertThat(bootstrap.path("availableWorkspaces").findValuesAsText("requiresStepUp")).containsOnly("false");
    }

    @Test
    void webAdminLoginSetsSessionCookieWithoutChallenge() throws Exception {
        MvcResult result = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginName", "flow.admin.two", "password", "FlowAdminTwo2026", "channel", "WEB_ADMIN")))).andReturn();
        assertThat(body(result).path("status").asText()).isEqualTo("AUTHENTICATED");
        assertThat(result.getResponse().getCookie("SHTONG_SESSION")).isNotNull();
    }

    @Test
    void removedMfaAndStepUpRoutesAreNotFound() throws Exception {
        String token = token(login("flow.admin.one", "FlowAdminOne2026"));
        assertThat(mvc.perform(post("/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"x\",\"code\":\"123456\"}")).andReturn().getResponse().getStatus()).isEqualTo(404);
        MvcResult stepUp = call(post("/v1/session/step-up").content("{\"targetWorkspace\":\"SYSTEM_ADMIN\"}"), token);
        assertThat(stepUp.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(stepUp).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void workspaceSwitchNeedsNoSecondFactor() throws Exception {
        String adminOne = token(login("flow.admin.one", "FlowAdminOne2026"));
        Map<String, String> org = organization(adminOne);
        UUID adminTwoId = accountId(adminOne, "flow.admin.two");
        MvcResult assigned = call(post("/v1/system/accounts/" + adminTwoId + "/role-assignments").content(json.writeValueAsString(Map.of(
                "roleCode", "TEACHER_ADMIN", "scopeCeilingOrgUnitIds", List.of(org.get("GRADE")),
                "validFrom", Instant.now().toString(), "reason", "test assignment"))), adminOne);
        assertThat(assigned.getResponse().getStatus()).isEqualTo(201);

        JsonNode login = login("flow.admin.two", "FlowAdminTwo2026");
        assertThat(login.at("/session/context/workspace").asText()).isEqualTo("ADMIN");
        String token = token(login);
        assertThat(call(get("/v1/system/accounts"), token).getResponse().getStatus()).isEqualTo(403);
        MvcResult switched = call(post("/v1/session/context").content("{\"workspace\":\"SYSTEM_ADMIN\"}"), token);
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/v1/system/accounts"), token).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordPolicyRequiresUpperLowerAndDigit() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        Map<String, String> org = organization(admin);
        JsonNode created = createStudent(admin, org.get("CLASS"));
        String temporary = created.at("/credential/temporaryPassword").asText();
        assertThat(AccountAccessService.meetsPasswordPolicy(temporary)).isTrue();

        JsonNode login = login(created.at("/account/loginName").asText(), temporary);
        assertThat(login.path("status").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        String token = token(login);
        for (String weak : List.of("alllowercase1", "ALLUPPERCASE1", "NoDigitsHere", "Sh0rt"))
            assertThat(call(post("/v1/auth/password/change").content(json.writeValueAsString(Map.of("currentPassword", temporary, "newPassword", weak))), token)
                    .getResponse().getStatus()).as(weak).isEqualTo(400);
        MvcResult changed = call(post("/v1/auth/password/change").content(json.writeValueAsString(Map.of("currentPassword", temporary, "newPassword", "Student2026Ok"))), token);
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void accountCreationReplayDoesNotRevealTheTemporaryPasswordAgain() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        Map<String, String> org = organization(admin);
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = studentRequest(org.get("CLASS"));
        JsonNode first = body(call(post("/v1/system/accounts").content(json.writeValueAsString(request)).header("Idempotency-Key", key), admin));
        String temporary = first.at("/credential/temporaryPassword").asText();
        assertThat(temporary).hasSize(20);
        JsonNode replay = body(call(post("/v1/system/accounts").content(json.writeValueAsString(request)).header("Idempotency-Key", key), admin));
        assertThat(replay.path("credential").isMissingNode()).isTrue();
        assertThat(replay.path("credentialDeliveryStatus").asText()).isEqualTo("ALREADY_DELIVERED");
        assertThat(replay.at("/account/id").asText()).isEqualTo(first.at("/account/id").asText());
        assertThat(new String(service.captureState(), StandardCharsets.ISO_8859_1)).doesNotContain(temporary);
    }

    @Test
    void resetCredentialFileIsDeliveredOnceAndThenDiscarded() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        Map<String, String> org = organization(admin);
        String accountId = createStudent(admin, org.get("CLASS")).at("/account/id").asText();
        JsonNode command = body(call(post("/v1/system/accounts/commands/reset-password").content(json.writeValueAsString(
                Map.of("accountIds", List.of(accountId), "expectedCount", 1, "reason", "test reset"))), admin));
        String jobId = command.path("jobId").asText();
        String url = body(call(post("/v1/system/account-commands/" + jobId + "/credential-ticket"), admin)).path("url").asText();
        MvcResult download = call(get(url), admin);
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        String csv = download.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String password = csv.lines().skip(1).findFirst().orElseThrow().split(",")[2].replace("\"", "");
        assertThat(AccountAccessService.meetsPasswordPolicy(password)).isTrue();
        assertThat(call(get(url), admin).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(post("/v1/system/account-commands/" + jobId + "/credential-ticket"), admin).getResponse().getStatus()).isEqualTo(404);
        assertThat(new String(service.captureState(), StandardCharsets.ISO_8859_1)).doesNotContain(password);
    }

    @Test
    void failedPasswordChangeIsAuditedUnderItsOwnCodeAndRequestId() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        MvcResult failed = call(post("/v1/auth/password/change").content(json.writeValueAsString(Map.of("currentPassword", "WrongPassword1", "newPassword", "Another2026Pass"))), admin);
        assertThat(failed.getResponse().getStatus()).isEqualTo(400);
        String requestId = failed.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank().isEqualTo(body(failed).path("requestId").asText());
        List<JsonNode> events = audit(admin, requestId);
        assertThat(events).extracting(e -> e.path("eventCode").asText()).containsExactly("AUTH.PASSWORD.CHANGE_FAILED");
        assertThat(events.getFirst().path("outcome").asText()).isEqualTo("FAILED");
    }

    @Test
    void deniedRequestIsAuditedExactlyOnce() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        Map<String, String> org = organization(admin);
        JsonNode student = createStudent(admin, org.get("CLASS"));
        String token = token(login(student.at("/account/loginName").asText(), student.at("/credential/temporaryPassword").asText()));
        MvcResult denied = call(get("/v1/system/accounts"), token);
        assertThat(denied.getResponse().getStatus()).isEqualTo(401);
        String requestId = denied.getResponse().getHeader("X-Request-Id");
        assertThat(audit(admin, requestId)).extracting(e -> e.path("outcome").asText()).containsExactly("DENIED");
    }

    @Test
    void auditDetailCarriesTheFullHashChainFields() throws Exception {
        String admin = token(login("flow.admin.one", "FlowAdminOne2026"));
        List<JsonNode> events = audit(admin, null);
        JsonNode detail = body(call(get("/v1/system/audit/events/" + events.getFirst().path("eventId").asText()), admin));
        assertThat(detail.path("payloadHash").asText()).hasSize(64);
        assertThat(detail.path("previousPayloadHash").asText()).hasSize(64);
        assertThat(service.auditLedgerRecords()).allMatch(r -> r.hashVersion() == AccountAccessService.AUDIT_HASH_FULL);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private JsonNode login(String loginName, String password) throws Exception {
        return body(mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginName", loginName, "password", password, "channel", "ANDROID")))).andReturn());
    }

    private static String token(JsonNode login) { return login.at("/session/mobileTokens/accessToken").asText(); }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        MockHttpServletRequestBuilder prepared = request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        var built = prepared.buildRequest(new org.springframework.mock.web.MockServletContext());
        if (!"GET".equals(built.getMethod()) && built.getHeader("Idempotency-Key") == null) prepared.header("Idempotency-Key", UUID.randomUUID().toString());
        return mvc.perform(prepared).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isEmpty() ? json.nullNode() : json.readTree(text);
    }

    private Map<String, String> organization(String token) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, String> ids = new LinkedHashMap<>();
        String parent = null;
        for (String type : List.of("SCHOOL", "COLLEGE", "GRADE", "CLASS")) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("type", type); request.put("parentId", parent); request.put("code", type + "-" + suffix);
            request.put("name", type + " " + suffix); request.put("expectedVersion", 0); request.put("reason", "test organization");
            MvcResult result = call(post("/v1/system/org-units").content(json.writeValueAsString(request)), token);
            assertThat(result.getResponse().getStatus()).as(type).isEqualTo(201);
            parent = body(result).path("id").asText();
            ids.put(type, parent);
        }
        return ids;
    }

    private Map<String, Object> studentRequest(String classId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return Map.of("loginName", "student." + suffix, "displayName", "Student " + suffix, "personNo", "P" + suffix,
                "classId", classId, "roleAssignments", List.of(Map.of("roleCode", "USER", "scopeCeilings", List.of())), "reason", "test account");
    }

    private JsonNode createStudent(String token, String classId) throws Exception {
        MvcResult result = call(post("/v1/system/accounts").content(json.writeValueAsString(studentRequest(classId))), token);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return body(result);
    }

    private UUID accountId(String token, String loginName) throws Exception {
        JsonNode page = body(call(get("/v1/system/accounts").param("q", loginName), token));
        for (JsonNode item : page.path("items")) if (item.path("loginName").asText().equals(loginName)) return UUID.fromString(item.path("id").asText());
        throw new AssertionError("account not found: " + loginName);
    }

    private List<JsonNode> audit(String token, String requestId) throws Exception {
        MockHttpServletRequestBuilder request = get("/v1/system/audit/events")
                .param("occurredFrom", Instant.now().minus(1, ChronoUnit.HOURS).toString())
                .param("occurredTo", Instant.now().plus(1, ChronoUnit.MINUTES).toString()).param("limit", "200");
        if (requestId != null) request.param("requestId", requestId);
        List<JsonNode> items = new java.util.ArrayList<>();
        body(call(request, token)).path("items").forEach(items::add);
        return items;
    }
}
