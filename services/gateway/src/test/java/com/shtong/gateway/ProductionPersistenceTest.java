package com.shtong.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production profile against a real PostgreSQL server. Runs only when
 * {@code SHTONG_IT_POSTGRES_URL} points at a server where {@code SHTONG_IT_POSTGRES_USER}
 * (default {@code postgres}) may create databases and roles; each test uses a fresh database.
 */
@EnabledIfEnvironmentVariable(named = "SHTONG_IT_POSTGRES_URL", matches = "jdbc:postgresql://.+")
class ProductionPersistenceTest {
    private static final String ADMIN_URL = System.getenv("SHTONG_IT_POSTGRES_URL");
    private static final String ADMIN_USER = System.getenv().getOrDefault("SHTONG_IT_POSTGRES_USER", "postgres");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("SHTONG_IT_POSTGRES_PASSWORD", "");
    private static final String RUNTIME_USER = "shtong_it_app";
    private static final String RUNTIME_PASSWORD = "shtong-it-runtime-password";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void persistsIncrementallyAndStaysConsistentAcrossInstances() throws Exception {
        String db = createDatabase();
        try (var a = start(db); var b = start(db)) {
            String token = adminToken(a);
            Map<String, String> org = organization(a, token, "A");
            JsonNode student = post(a, "/v1/system/accounts", token, Map.of("loginName", "it.student", "displayName", "IT Student", "personNo", "IT0001",
                    "classId", org.get("CLASS"), "roleAssignments", List.of(Map.of("roleCode", "USER", "scopeCeilings", List.of())), "reason", "it account")).body();
            String studentId = student.at("/account/id").asText();

            long version = scalar(db, "SELECT state_version FROM admin.runtime_checkpoint");
            long events = scalar(db, "SELECT count(*) FROM audit.audit_event");
            long batches = scalar(db, "SELECT count(*) FROM audit.manifest WHERE manifest_kind='TRANSACTION_BATCH'");
            assertThat(scalar(db, "SELECT count(*) FROM audit.manifest WHERE manifest_kind='RUNTIME_CHECKPOINT'")).isZero();
            assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event WHERE hash_version<>2")).isZero();

            // A read appends its audit rows but leaves the encrypted checkpoint untouched.
            assertThat(get(a, "/v1/system/accounts", token).status()).isEqualTo(200);
            assertThat(scalar(db, "SELECT state_version FROM admin.runtime_checkpoint")).isEqualTo(version);
            assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event")).isGreaterThan(events);
            assertThat(scalar(db, "SELECT count(*) FROM audit.manifest WHERE manifest_kind='TRANSACTION_BATCH'")).isEqualTo(batches + 1);

            // The idle lifecycle dispatcher (500 ms period here) writes nothing.
            long idleEvents = scalar(db, "SELECT count(*) FROM audit.audit_event"), idleManifests = scalar(db, "SELECT count(*) FROM audit.manifest");
            Thread.sleep(2000);
            assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event")).isEqualTo(idleEvents);
            assertThat(scalar(db, "SELECT count(*) FROM audit.manifest")).isEqualTo(idleManifests);

            // Instance B sees A's session and data; A sees B's writes.
            assertThat(get(b, "/v1/system/accounts/" + studentId, token).status()).isEqualTo(200);
            String fromB = post(b, "/v1/system/org-units", token, orgRequest("SCHOOL", null, "FROM-B")).body().path("id").asText();
            assertThat(get(a, "/v1/system/org-units", token).body().findValuesAsText("id")).contains(fromB);

            // Concurrent writes through both instances keep one linear chain.
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                ConfigurableApplicationContext target = i % 2 == 0 ? a : b;
                String code = "CONCURRENT-" + i;
                results.add(pool.submit((Callable<Integer>) () -> post(target, "/v1/system/org-units", token, orgRequest("SCHOOL", null, code)).status()));
            }
            for (Future<Integer> result : results) assertThat(result.get()).isEqualTo(201);
            pool.shutdown();

            // A rejected request commits its denial audit.
            JsonNode studentLogin = login(a, "it.student", student.at("/credential/temporaryPassword").asText());
            Response denied = get(a, "/v1/system/accounts", studentLogin.at("/session/mobileTokens/accessToken").asText());
            assertThat(denied.status()).isEqualTo(401);
            assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event WHERE outcome='DENIED' AND request_id='" + denied.requestId() + "'")).isEqualTo(1);
        }
        try (var c = start(db)) {
            String token = adminToken(c);
            List<String> codes = get(c, "/v1/system/org-units", token).body().findValuesAsText("code");
            assertThat(codes).contains("FROM-B").filteredOn(code -> code.startsWith("CONCURRENT-")).hasSize(24);
        }
        dropDatabase(db);
    }

    @Test
    void tamperedLedgerRowIsRejectedAtStartup() throws Exception {
        String db = createDatabase();
        try (var a = start(db)) { organization(a, adminToken(a), "T"); }
        try (Connection connection = connect(db); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit.audit_event DISABLE TRIGGER audit_event_append_only");
            statement.execute("UPDATE audit.audit_event SET changes='[{\"field\":\"forged\"}]'::jsonb WHERE event_code='ORGANIZATION.CREATED'");
            statement.execute("ALTER TABLE audit.audit_event ENABLE TRIGGER audit_event_append_only");
        }
        assertThatThrownBy(() -> start(db).close()).hasStackTraceContaining("does not match its hash");
        dropDatabase(db);
    }

    @Test
    void upgradesALedgerWrittenWithLegacySummaryHashes() throws Exception {
        String db = createDatabase();
        Flyway.configure().dataSource(url(db), ADMIN_USER, ADMIN_PASSWORD).locations("classpath:db/migration").target("21").load().migrate();
        AccountAccessService hasher = new AccountAccessService();
        String previous = HexFormat.of().formatHex(new byte[32]);
        try (Connection connection = connect(db)) {
            for (int i = 0; i < 3; i++) {
                UUID id = UUID.randomUUID();
                Instant at = Instant.now().minus(10 - i, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
                String hash = hasher.auditHash(AccountAccessService.AUDIT_HASH_LEGACY, id, "AUTH.LOGIN.FAILED", at, null, null, "ANDROID", "DENY", "DENIED", "legacy-" + i, "invalid_credentials", List.of(), List.of(), List.of(), List.of(), previous);
                var insert = connection.prepareStatement("INSERT INTO audit.audit_event(recorded_date,event_id,event_code,occurred_at,request_id,workspace,channel,authorization_decision,outcome,payload_hash,actor_account_id,reason_code,previous_payload_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)");
                insert.setObject(1, at.atZone(ZoneOffset.UTC).toLocalDate()); insert.setObject(2, id); insert.setString(3, "AUTH.LOGIN.FAILED");
                insert.setObject(4, OffsetDateTime.ofInstant(at, ZoneOffset.UTC)); insert.setString(5, "legacy-" + i); insert.setString(6, null); insert.setString(7, "ANDROID");
                insert.setString(8, "DENY"); insert.setString(9, "DENIED"); insert.setBytes(10, HexFormat.of().parseHex(hash)); insert.setObject(11, null);
                insert.setString(12, "invalid_credentials"); insert.setBytes(13, HexFormat.of().parseHex(previous));
                insert.executeUpdate();
                previous = hash;
            }
        }
        try (var a = start(db)) {
            assertThat(get(a, "/v1/system/accounts", adminToken(a)).status()).isEqualTo(200);
        }
        assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event WHERE hash_version=1")).isEqualTo(3);
        assertThat(scalar(db, "SELECT count(*) FROM audit.audit_event WHERE hash_version=2")).isGreaterThan(0);
        try (var b = start(db)) { assertThat(adminToken(b)).isNotBlank(); }
        dropDatabase(db);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private record Response(int status, JsonNode body, String requestId) {}

    private ConfigurableApplicationContext start(String db) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("SHTONG_DATABASE_URL", url(db));
        properties.put("SHTONG_DATABASE_USER", RUNTIME_USER);
        properties.put("SHTONG_DATABASE_PASSWORD", RUNTIME_PASSWORD);
        properties.put("SHTONG_MIGRATION_DATABASE_USER", ADMIN_USER);
        properties.put("SHTONG_MIGRATION_DATABASE_PASSWORD", ADMIN_PASSWORD);
        properties.put("SHTONG_STATE_ENCRYPTION_KEY", "integration-test-state-encryption-key-0123456789");
        properties.put("SHTONG_REFRESH_TOKEN_PEPPER", "integration-test-refresh-token-pepper-0123456789");
        properties.put("SHTONG_BOOTSTRAP_SECRET", "integration-test-bootstrap-secret-0123456789");
        properties.put("SHTONG_BOOTSTRAP_ADMIN_ONE_LOGIN", "it.admin.one");
        properties.put("SHTONG_BOOTSTRAP_ADMIN_ONE_PASSWORD", "ItAdminOne2026");
        properties.put("SHTONG_BOOTSTRAP_ADMIN_TWO_LOGIN", "it.admin.two");
        properties.put("SHTONG_BOOTSTRAP_ADMIN_TWO_PASSWORD", "ItAdminTwo2026");
        return new SpringApplicationBuilder(GatewayApplication.class).profiles("production").properties(properties)
                .run("--server.port=0", "--shtong.medical-insurance.sync-delay-ms=500", "--shtong.maintenance.prune-delay-ms=3600000");
    }

    private String adminToken(ConfigurableApplicationContext context) throws Exception {
        JsonNode login = login(context, "it.admin.one", "ItAdminOne2026");
        assertThat(login.path("status").asText()).isEqualTo("AUTHENTICATED");
        return login.at("/session/mobileTokens/accessToken").asText();
    }

    private JsonNode login(ConfigurableApplicationContext context, String loginName, String password) throws Exception {
        return send(context, "POST", "/v1/auth/login", null, Map.of("loginName", loginName, "password", password, "channel", "ANDROID")).body();
    }

    private Map<String, String> organization(ConfigurableApplicationContext context, String token, String suffix) throws Exception {
        Map<String, String> ids = new LinkedHashMap<>();
        String parent = null;
        for (String type : List.of("SCHOOL", "COLLEGE", "GRADE", "CLASS")) {
            Response created = post(context, "/v1/system/org-units", token, orgRequest(type, parent, type + "-" + suffix));
            assertThat(created.status()).as(type).isEqualTo(201);
            parent = created.body().path("id").asText();
            ids.put(type, parent);
        }
        return ids;
    }

    private static Map<String, Object> orgRequest(String type, String parent, String code) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", type); request.put("parentId", parent); request.put("code", code); request.put("name", code);
        request.put("expectedVersion", 0); request.put("reason", "integration test");
        return request;
    }

    private Response get(ConfigurableApplicationContext context, String path, String token) throws Exception { return send(context, "GET", path, token, null); }
    private Response post(ConfigurableApplicationContext context, String path, String token, Object body) throws Exception { return send(context, "POST", path, token, body); }

    private Response send(ConfigurableApplicationContext context, String method, String path, String token, Object body) throws Exception {
        String port = context.getEnvironment().getProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (!"GET".equals(method)) request.header("Idempotency-Key", UUID.randomUUID().toString());
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        return new Response(response.statusCode(), node, response.headers().firstValue("X-Request-Id").orElse(null));
    }

    private static String url(String db) { return ADMIN_URL.replaceFirst("/[^/?]*(\\?.*)?$", "/" + db + "$1"); }

    private static Connection connect(String db) throws Exception { return DriverManager.getConnection(url(db), ADMIN_USER, ADMIN_PASSWORD); }

    private static String createDatabase() throws Exception {
        String db = "shtong_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + db);
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='shtong_runtime') THEN CREATE ROLE shtong_runtime NOLOGIN; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + RUNTIME_USER + "') THEN CREATE ROLE " + RUNTIME_USER + " LOGIN INHERIT PASSWORD '" + RUNTIME_PASSWORD + "'; END IF; END $$");
            statement.execute("GRANT shtong_runtime TO " + RUNTIME_USER);
        }
        return db;
    }

    private static void dropDatabase(String db) throws Exception {
        try (Connection connection = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
        }
    }

    private static long scalar(String db, String sql) throws Exception {
        try (Connection connection = connect(db); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
