package com.shtong.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLedgerTest {
    private static final String ZERO = HexFormat.of().formatHex(new byte[32]);

    private static AccountAccessService serviceWithEvents() {
        AccountAccessService service = new AccountAccessService();
        service.bootstrapInitialAdmins("ledger.one", "LedgerOne2026", "ledger.two", "LedgerTwo2026");
        assertThat(service.login("ledger.one", "LedgerOne2026", "ANDROID", "req-ok").status()).isEqualTo("AUTHENTICATED");
        assertThat(service.login("ledger.one", "wrong", "ANDROID", "req-bad").status()).isEqualTo("DENIED");
        return service;
    }

    @Test
    void untouchedLedgerVerifiesAndRebuildsInChainOrder() {
        AccountAccessService service = serviceWithEvents();
        List<AccountAccessService.AuditLedgerRecord> records = new ArrayList<>(service.auditLedgerRecords());
        byte[] tail = service.auditTailHash();
        java.util.Collections.reverse(records);
        service.replaceAuditLedger(records);
        assertThat(service.auditTailHash()).isEqualTo(tail);
        assertThat(service.auditLedgerRecords()).extracting(AccountAccessService.AuditLedgerRecord::code)
                .startsWith("CONTROL.EXECUTED", "AUTH.LOGIN.ATTEMPTED", "AUTH.LOGIN.SUCCEEDED");
    }

    @Test
    void everyStoredFieldIsCoveredByTheHash() {
        AccountAccessService service = serviceWithEvents();
        List<AccountAccessService.AuditLedgerRecord> records = service.auditLedgerRecords();
        AccountAccessService.AuditLedgerRecord login = records.stream().filter(r -> r.code().equals("AUTH.LOGIN.SUCCEEDED")).findFirst().orElseThrow();
        List<AccountAccessService.AuditLedgerRecord> tampered = List.of(
                copy(login, b -> b.occurredAt = login.occurredAt().plusSeconds(1)),
                copy(login, b -> b.id = UUID.randomUUID()),
                copy(login, b -> b.changes = List.of(Map.of("field", "reasonCode", "after", "edited"))),
                copy(login, b -> b.evidence = List.of(Map.of("decision", "DENY"))),
                copy(login, b -> b.resources = List.of()),
                copy(login, b -> b.subjects = List.of(UUID.randomUUID())),
                copy(login, b -> b.reason = "edited"),
                copy(login, b -> b.outcome = "FAILED"));
        for (AccountAccessService.AuditLedgerRecord forged : tampered) {
            List<AccountAccessService.AuditLedgerRecord> ledger = records.stream().map(r -> r.id().equals(login.id()) ? forged : r).toList();
            assertThatThrownBy(() -> new AccountAccessService().replaceAuditLedger(ledger)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void contentSurvivesAJsonRoundTripLikeThePostgresJsonbColumns() throws Exception {
        AccountAccessService service = serviceWithEvents();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var type = new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {};
        List<AccountAccessService.AuditLedgerRecord> reloaded = service.auditLedgerRecords().stream().map(r -> {
            try {
                return copy(r, b -> {
                    try {
                        b.resources = mapper.readValue(mapper.writeValueAsString(r.resources()), type);
                        b.changes = mapper.readValue(mapper.writeValueAsString(r.changes()), type);
                        b.evidence = mapper.readValue(mapper.writeValueAsString(r.evidence()), type);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                });
            } catch (Exception e) { throw new IllegalStateException(e); }
        }).toList();
        new AccountAccessService().replaceAuditLedger(reloaded);
    }

    @Test
    void legacySummaryHashedRowsStillVerify() {
        AccountAccessService service = new AccountAccessService();
        UUID id = UUID.randomUUID(), actor = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String hash = service.auditHash(AccountAccessService.AUDIT_HASH_LEGACY, id, "AUTH.LOGIN.SUCCEEDED", at, actor, "USER", "ANDROID",
                "ALLOW", "SUCCESS", "req-legacy", null, List.of(actor), List.of(), List.of(), List.of(), ZERO);
        var legacy = new AccountAccessService.AuditLedgerRecord(id, AccountAccessService.AUDIT_HASH_LEGACY, "AUTH.LOGIN.SUCCEEDED", at, actor, "USER", "ANDROID",
                "ALLOW", "SUCCESS", "req-legacy", null, List.of(actor), List.of(Map.of("resourceType", "ACCOUNT_OR_RESOURCE")), List.of(), List.of(), hash, ZERO);
        service.replaceAuditLedger(List.of(legacy));
        assertThat(HexFormat.of().formatHex(service.auditTailHash())).isEqualTo(hash);
        assertThatThrownBy(() -> new AccountAccessService().replaceAuditLedger(List.of(copy(legacy, b -> b.code = "AUTH.LOGIN.FAILED"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extensionMustContinueTheLocalTail() {
        AccountAccessService source = serviceWithEvents();
        List<AccountAccessService.AuditLedgerRecord> all = source.auditLedgerRecords();
        AccountAccessService replica = new AccountAccessService();
        replica.replaceAuditLedger(all.subList(0, 2));
        replica.extendAuditLedger(all.subList(2, all.size()));
        assertThat(replica.auditTailHash()).isEqualTo(source.auditTailHash());
        AccountAccessService gap = new AccountAccessService();
        gap.replaceAuditLedger(all.subList(0, 1));
        assertThatThrownBy(() -> gap.extendAuditLedger(all.subList(2, all.size()))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forkedOrBrokenChainsAreRejected() {
        List<AccountAccessService.AuditLedgerRecord> all = serviceWithEvents().auditLedgerRecords();
        List<AccountAccessService.AuditLedgerRecord> broken = new ArrayList<>(all);
        broken.remove(1);
        assertThatThrownBy(() -> new AccountAccessService().replaceAuditLedger(broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pruningDropsRevokedSessionsAndExpiredRecords() throws Exception {
        AccountAccessService service = new AccountAccessService();
        service.bootstrapInitialAdmins("prune.one", "PruneOne2026", "prune.two", "PruneTwo2026");
        String token = service.login("prune.one", "PruneOne2026", "ANDROID", "req-1").transportToken();
        MockHttpServletRequest logout = new MockHttpServletRequest("POST", "/v1/auth/logout");
        logout.addHeader("Authorization", "Bearer " + token);
        service.logout(logout, UUID.randomUUID().toString(), "req-2");
        Map<String, AccountAccessService.Session> sessions = field(service, "sessions");
        assertThat(service.pruneExpiredState()).isZero(); // still within the retention window
        sessions.values().forEach(s -> s.revokedAt = Instant.now().minus(2, ChronoUnit.HOURS));
        assertThat(service.pruneExpiredState()).isGreaterThan(0);
        assertThat(sessions).isEmpty();
        Map<?, ?> refresh = field(service, "refreshTokens");
        assertThat(refresh).isEmpty();
        MockHttpServletRequest reuse = new MockHttpServletRequest("GET", "/v1/session/bootstrap");
        reuse.addHeader("Authorization", "Bearer " + token);
        assertThatThrownBy(() -> service.bootstrap(reuse, "req-3")).isInstanceOf(ApiError.class).hasMessage("SESSION_REVOKED");
    }

    @Test
    void passwordPolicy() {
        assertThat(AccountAccessService.meetsPasswordPolicy("Abcdefg1")).isTrue();
        assertThat(AccountAccessService.meetsPasswordPolicy("Abcdef1")).isFalse();
        assertThat(AccountAccessService.meetsPasswordPolicy("abcdefg1")).isFalse();
        assertThat(AccountAccessService.meetsPasswordPolicy("ABCDEFG1")).isFalse();
        assertThat(AccountAccessService.meetsPasswordPolicy("Abcdefgh")).isFalse();
        assertThat(AccountAccessService.meetsPasswordPolicy("Ａbcdefg1")).isFalse(); // full-width letters do not count
        assertThat(AccountAccessService.meetsPasswordPolicy("A1b" + "x".repeat(126))).isFalse();
        assertThat(AccountAccessService.meetsPasswordPolicy("密码Abc12345 !")).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = AccountAccessService.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static final class Builder {
        UUID id; String code; Instant occurredAt; List<UUID> subjects; List<Map<String, Object>> resources, changes, evidence; String reason, outcome;
    }

    private static AccountAccessService.AuditLedgerRecord copy(AccountAccessService.AuditLedgerRecord r, java.util.function.Consumer<Builder> change) {
        Builder b = new Builder();
        b.id = r.id(); b.code = r.code(); b.occurredAt = r.occurredAt(); b.subjects = r.subjects(); b.resources = r.resources();
        b.changes = r.changes(); b.evidence = r.evidence(); b.reason = r.reason(); b.outcome = r.outcome();
        change.accept(b);
        return new AccountAccessService.AuditLedgerRecord(b.id, r.hashVersion(), b.code, b.occurredAt, r.actor(), r.workspace(), r.channel(), r.decision(),
                b.outcome, r.requestId(), b.reason, b.subjects, new ArrayList<>(b.resources), new ArrayList<>(b.changes), new ArrayList<>(b.evidence),
                r.payloadHash(), r.previousPayloadHash());
    }

}
