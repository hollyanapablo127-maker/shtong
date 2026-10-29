package com.shtong.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayOutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Production persistence: one encrypted checkpoint row for the domain state plus the append-only audit ledger.
 * All writers serialize on a transaction-scoped advisory lock, so several gateway instances stay consistent.
 */
@Repository
@Profile("production")
class PostgresRuntimeStateRepository {
    private static final long LOCK_KEY = 0x5348544F4E47L; // "SHTONG"
    private static final String ZERO_HASH = HexFormat.of().formatHex(new byte[32]);
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String encryptionKey;

    record SyncPoint(long stateVersion, long auditSequence) {}
    record Checkpoint(byte[] payload, long version) {}

    PostgresRuntimeStateRepository(JdbcTemplate jdbc, ObjectMapper json, @Value("${shtong.state-encryption-key}") String encryptionKey) {
        this.jdbc = jdbc;
        this.json = json;
        this.encryptionKey = encryptionKey;
        if (encryptionKey == null || encryptionKey.length() < 32) throw new IllegalStateException("SHTONG_STATE_ENCRYPTION_KEY must contain at least 32 characters");
    }

    /** Serializes all gateway instances for the rest of the current transaction. */
    void lock() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, LOCK_KEY);
    }

    SyncPoint syncPoint() {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT state_version FROM admin.runtime_checkpoint WHERE checkpoint_key='AUTHORITATIVE'), 0),
                       COALESCE((SELECT max(ledger_seq) FROM audit.audit_event), 0)
                """, (rs, row) -> new SyncPoint(rs.getLong(1), rs.getLong(2)));
    }

    Optional<Checkpoint> load() {
        List<Checkpoint> values = jdbc.query("""
                SELECT pgp_sym_decrypt_bytea(payload, ?), payload_sha256, state_version
                FROM admin.runtime_checkpoint WHERE checkpoint_key='AUTHORITATIVE'
                """, (rs, row) -> {
            byte[] payload = rs.getBytes(1);
            if (!java.security.MessageDigest.isEqual(rs.getBytes(2), sha256(payload))) throw new IllegalStateException("Authoritative checkpoint digest mismatch");
            return new Checkpoint(payload, rs.getLong(3));
        }, encryptionKey);
        return values.stream().findFirst();
    }

    /** Writes the checkpoint and returns its new version. */
    long save(byte[] payload) {
        return jdbc.queryForObject("""
                INSERT INTO admin.runtime_checkpoint(checkpoint_key,payload,payload_sha256,state_version,updated_at)
                VALUES ('AUTHORITATIVE',pgp_sym_encrypt_bytea(?,?,'cipher-algo=aes256,compress-algo=1'),?,1,now())
                ON CONFLICT (checkpoint_key) DO UPDATE SET payload=EXCLUDED.payload,payload_sha256=EXCLUDED.payload_sha256,
                  state_version=admin.runtime_checkpoint.state_version+1,updated_at=now()
                RETURNING state_version
                """, Long.class, payload, encryptionKey, sha256(payload));
    }

    /**
     * Appends the events produced by one operation. The first event must link to the persisted tail; the batch
     * is sealed with a manifest over its payload hashes. Returns the ledger sequence of the last row.
     */
    long appendAudit(List<AccountAccessService.AuditLedgerRecord> records) {
        String tail = tailHash();
        if (!tail.equals(records.getFirst().previousPayloadHash())) throw new IllegalStateException("Audit batch does not extend the persisted ledger tail");
        long sequence = 0;
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        for (var event : records) {
            var recordedDate = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            byte[] payloadHash = HexFormat.of().parseHex(event.payloadHash());
            sequence = jdbc.queryForObject("""
                    INSERT INTO audit.audit_event(recorded_date,event_id,event_code,occurred_at,request_id,workspace,channel,
                      authorization_decision,outcome,payload_hash,actor_account_id,reason_code,previous_payload_hash,
                      authorization_evidence,resources,changes,hash_version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
                    RETURNING ledger_seq
                    """, Long.class, recordedDate, event.id(), event.code(), OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                    event.requestId(), event.workspace(), event.channel(), event.decision(), event.outcome(), payloadHash,
                    event.actor(), event.reason(), HexFormat.of().parseHex(event.previousPayloadHash()),
                    json(event.evidence()), json(event.resources()), json(event.changes()), (short) event.hashVersion());
            for (int index = 0; index < event.subjects().size(); index++)
                jdbc.update("INSERT INTO audit.audit_event_subject(recorded_date,event_id,ordinal,subject_id) VALUES (?,?,?,?)", recordedDate, event.id(), index, event.subjects().get(index));
            jdbc.update("INSERT INTO audit.outbox(id,event_recorded_date,event_id,state) VALUES (gen_random_uuid(),?,?,'PENDING')", recordedDate, event.id());
            root.writeBytes(payloadHash);
        }
        jdbc.update("INSERT INTO audit.manifest(manifest_kind,item_count,root_hash) VALUES ('TRANSACTION_BATCH',?,?)", records.size(), sha256(root.toByteArray()));
        return sequence;
    }

    /** Ledger rows with a sequence above {@code afterSequence}, in insertion order. */
    List<AccountAccessService.AuditLedgerRecord> auditAfter(long afterSequence) {
        Map<UUID, List<UUID>> subjects = new LinkedHashMap<>();
        jdbc.query("""
                SELECT s.event_id, s.subject_id FROM audit.audit_event_subject s
                JOIN audit.audit_event e ON e.recorded_date=s.recorded_date AND e.event_id=s.event_id
                WHERE e.ledger_seq > ? ORDER BY s.event_id, s.ordinal
                """, (ResultSet rs) -> { subjects.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>()).add(rs.getObject(2, UUID.class)); }, afterSequence);
        return jdbc.query("""
                SELECT event_id,hash_version,event_code,occurred_at,actor_account_id,workspace,channel,authorization_decision,outcome,
                       request_id,reason_code,resources::text,changes::text,authorization_evidence::text,payload_hash,previous_payload_hash
                FROM audit.audit_event WHERE ledger_seq > ? ORDER BY ledger_seq
                """, (rs, row) -> record(rs, subjects), afterSequence);
    }

    private AccountAccessService.AuditLedgerRecord record(ResultSet rs, Map<UUID, List<UUID>> subjects) throws SQLException {
        UUID id = rs.getObject("event_id", UUID.class);
        return new AccountAccessService.AuditLedgerRecord(id, rs.getInt("hash_version"), rs.getString("event_code"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getObject("actor_account_id", UUID.class),
                rs.getString("workspace"), rs.getString("channel"), rs.getString("authorization_decision"), rs.getString("outcome"),
                rs.getString("request_id"), rs.getString("reason_code"), List.copyOf(subjects.getOrDefault(id, List.of())),
                list(rs.getString("resources")), list(rs.getString("changes")), list(rs.getString("authorization_evidence")),
                HexFormat.of().formatHex(rs.getBytes("payload_hash")), HexFormat.of().formatHex(rs.getBytes("previous_payload_hash")));
    }

    private String tailHash() {
        List<byte[]> tail = jdbc.query("SELECT payload_hash FROM audit.audit_event ORDER BY ledger_seq DESC LIMIT 1", (rs, row) -> rs.getBytes(1));
        return tail.isEmpty() ? ZERO_HASH : HexFormat.of().formatHex(tail.getFirst());
    }

    private List<Map<String, Object>> list(String value) {
        try { return value == null ? List.of() : json.readValue(value, JSON_LIST); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to read audit evidence", e); }
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Unable to serialize audit evidence", e); }
    }

    private static byte[] sha256(byte[] value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
