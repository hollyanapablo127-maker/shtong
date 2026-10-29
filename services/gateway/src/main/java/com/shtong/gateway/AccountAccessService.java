package com.shtong.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Authoritative account-access domain state. Development keeps it in memory; the
 * production profile encrypts and checkpoints it in PostgreSQL inside each transaction. */
@Service
public class AccountAccessService {
    private static final Pattern LOGIN = Pattern.compile("^[\\p{L}\\p{N}._-]{1,100}$");
    private static final Set<String> ROLES = Set.of("USER", "TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN", "SYSTEM_ADMIN");
    private static final Set<String> ORG_TYPES = Set.of("SCHOOL", "COLLEGE", "GRADE", "CLASS");
    private static final Set<String> INITIAL_ROLES = Set.of("USER", "TEACHER_ADMIN", "STUDENT_ADMIN", "MEDICAL_INSURANCE_ADMIN");
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> ETHNICITIES = Set.of(
            "汉族","蒙古族","回族","藏族","维吾尔族","苗族","彝族","壮族","布依族","朝鲜族","满族","侗族",
            "瑶族","白族","土家族","哈尼族","哈萨克族","傣族","黎族","傈僳族","佤族","畲族","高山族","拉祜族",
            "水族","东乡族","纳西族","景颇族","柯尔克孜族","土族","达斡尔族","仫佬族","羌族","布朗族","撒拉族","毛难族",
            "仡佬族","锡伯族","阿昌族","普米族","塔吉克族","怒族","乌孜别克族","俄罗斯族","鄂温克族","崩龙族","保安族","裕固族",
            "京族","塔塔尔族","独龙族","鄂伦春族","赫哲族","门巴族","珞巴族","基诺族");
    private static final Set<String> SENSITIVE_PROFILE_FIELDS = Set.of("ID_CARD_NUMBER", "MOBILE_NUMBER", "CURRENT_ADDRESS", "HOUSEHOLD_ADDRESS");
    private static final Pattern MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern ID_CARD = Pattern.compile("^\\d{17}[0-9X]$");
    private static final Instant SESSION_END = Instant.parse("9999-12-31T23:59:59Z");
    private static final Duration REVOKED_SESSION_RETENTION = Duration.ofHours(1);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final Duration ONE_TIME_FILE_RETENTION = Duration.ofHours(24);
    private static final Duration TICKET_RETENTION = Duration.ofHours(1);
    private static final String TEMPORARY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    static final int AUDIT_HASH_LEGACY = 1;
    static final int AUDIT_HASH_FULL = 2;
    private static final Set<String> EVENTS = Set.of(
            "AUTH.LOGIN.ATTEMPTED", "AUTH.LOGIN.SUCCEEDED", "AUTH.LOGIN.FAILED", "AUTH.MFA.SUCCEEDED", "AUTH.MFA.FAILED",
            "AUTH.TOKEN.REFRESHED", "AUTH.TOKEN.REFRESH_FAILED", "AUTH.PASSWORD.CHANGE_REQUIRED", "AUTH.PASSWORD.CHANGED", "AUTH.PASSWORD.CHANGE_FAILED",
            "AUTH.PASSWORD.RESET", "AUTH.LOGOUT.SUCCEEDED", "AUTH.LOGOUT.FAILED", "SESSION.REVOKED", "SESSION.REVOKED_TOKEN_USED",
            "SESSION.CONTEXT_SWITCHED", "SESSION.CONTEXT_SWITCH_FAILED", "PROFILE.VIEWED", "PROFILE.UPDATED", "PROFILE.SENSITIVE_FIELD.REVEALED", "ACCOUNT.SEARCHED",
            "ACCOUNT.DETAIL_VIEWED", "ACCOUNT.CREATED", "ACCOUNT.UPDATED", "ACCOUNT.ENROLLMENT_CHANGED", "ACCOUNT.DELETED", "ACCOUNT.GRADUATED", "ACCOUNT.IMPORT_VALIDATION.REQUESTED",
            "ACCOUNT.IMPORT_VALIDATION.SUCCEEDED", "ACCOUNT.IMPORT_VALIDATION.FAILED", "ACCOUNT.IMPORT.COMMITTED", "ACCOUNT.IMPORT.COMMIT_FAILED",
            "ACCOUNT.IMPORT_RESULT.DOWNLOADED", "ACCOUNT.EXPORT.REQUESTED", "ACCOUNT.EXPORT.COMPLETED", "ACCOUNT.EXPORT.FAILED",
            "ACCOUNT.EXPORT.DOWNLOADED", "AUTHZ.ROLE.ASSIGNED", "AUTHZ.ROLE.REVOKED", "AUTHZ.SCOPE_CEILING.CHANGED",
            "AUTHZ.GRANT.CREATED", "AUTHZ.GRANT.REVOKED", "CONTROL.REQUESTED", "CONTROL.APPROVED", "CONTROL.REJECTED",
            "CONTROL.EXPIRED", "CONTROL.EXECUTED", "ORGANIZATION.SEARCHED", "ORGANIZATION.CREATED", "ORGANIZATION.UPDATED",
            "ORGANIZATION.DELETED", "AUDIT.SEARCHED", "AUDIT.DETAIL_VIEWED", "AUDIT.EXPORT.REQUESTED", "AUDIT.EXPORT.DOWNLOADED",
            "MODULE.SSO.TICKET_ISSUED", "MODULE.SSO.TICKET_EXCHANGED", "MODULE.SSO.SESSION_INTROSPECTED", "MODULE.SSO.SESSION_REVOKED", "MODULE.SSO.REJECTED");

    private final PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> loginIndex = new ConcurrentHashMap<>();
    private final Map<String, UUID> personIndex = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, OrgUnit> orgUnits = new ConcurrentHashMap<>();
    /** Audit ledger in chain order. Production persists it row by row, not in the state checkpoint. */
    private final Map<UUID, AuditEvent> auditEvents = new ConcurrentHashMap<>();
    private final List<UUID> auditOrder = new ArrayList<>();
    private final Set<String> failedRequestIds = new HashSet<>();
    private final Map<UUID, Command> commands = new ConcurrentHashMap<>();
    private final Map<UUID, ImportJob> importJobs = new ConcurrentHashMap<>();
    private final Map<UUID, ExportJob> exportJobs = new ConcurrentHashMap<>();
    private final Map<UUID, ExportJob> auditExportJobs = new ConcurrentHashMap<>();
    private final Map<UUID, ControlRequest> controls = new ConcurrentHashMap<>();
    private final Map<String, IdempotentResult> idempotency = new ConcurrentHashMap<>();
    private final Map<String, RefreshGrant> refreshTokens = new ConcurrentHashMap<>();
    private final Map<UUID, DownloadTicket> downloadTickets = new ConcurrentHashMap<>();
    /** Hashed one-time module tickets; plaintext values are only returned once to the caller. */
    private final Map<String, SsoTicket> medicalSsoTickets = new ConcurrentHashMap<>();
    private final Map<String, AttemptWindow> loginRate = new ConcurrentHashMap<>();
    private final AtomicLong authzRevision = new AtomicLong(1);
    private final AtomicLong orgRevision = new AtomicLong(1);
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom secureRandom = new SecureRandom();
    /** Canonical form for audit hashing: ISO time strings and recursively sorted object keys. */
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private byte[] previousAuditHash = new byte[32];

    @Value("${shtong.refresh-token-pepper:}") String refreshTokenPepper = "";
    @Value("${shtong.sso.adapter-id:medical-insurance}") String ssoAdapterId = "medical-insurance";
    @Value("${shtong.sso.adapter-secret:}") String ssoAdapterSecret = "";
    @Value("${shtong.medical-insurance.entry-url:/medical-insurance/sso/callback/}") String medicalInsuranceEntryUrl = "/medical-insurance/sso/callback/";
    @Value("${shtong.medical-insurance.mobile-entry-url:}") String medicalInsuranceMobileEntryUrl = "";
    @org.springframework.beans.factory.annotation.Autowired(required=false) AdministrativeDivisionService administrativeDivisions;

    public record LoginResult(String status, String challengeId, Instant expiresAt,
                              List<String> allowedActions, Map<String, Object> session,
                              @com.fasterxml.jackson.annotation.JsonIgnore String transportToken,
                              @com.fasterxml.jackson.annotation.JsonIgnore String transportRefreshToken) {}
    public record Principal(UUID accountId, String token, String channel, String workspace,
                            String restriction, long authzVersion, Instant expiresAt) {}
    public record OneTimeCredential(String temporaryPassword) implements Serializable {@Serial private static final long serialVersionUID=1L;}

    static final class Address implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        String provinceCode;
        String provinceName;
        String cityCode;
        String cityName;
        String districtCode;
        String districtName;
        String detail;
    }

    static final class Account implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        String loginName;
        String personNo;
        String displayName;
        String gender;
        String ethnicity;
        String idCardNumber;
        String mobileNumber;
        Address currentAddress;
        Address householdAddress;
        Instant profileCompletedAt;
        String lifecycle = "ACTIVE";
        String security = "NORMAL";
        String credentialState = "TEMPORARY";
        String passwordHash;
        UUID classId;
        Map<String, RoleAssignment> roles = new LinkedHashMap<>();
        Instant createdAt = Instant.now();
        Instant updatedAt = createdAt;
        Instant lastLoginAt;
        Instant graduatedAt;
        String graduatedReason;
        long medicalSyncedAuthzVersion;
        Instant medicalSyncAfter;
        long version = 1;
        long authzVersion = 1;
        long credentialVersion = 1;
        int failedAttemptCount;
        Instant lockedUntil;
        Deque<String> passwordHistory = new ArrayDeque<>();
    }
    static final class RoleAssignment implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        String roleCode;
        String status = "ACTIVE";
        Instant validFrom = Instant.now();
        Instant validTo;
        long version = 1;
        Set<UUID> ceilings = new LinkedHashSet<>();
    }
    static final class Session implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        final UUID accountId;
        final String channel;
        final String token;
        final Instant createdAt = Instant.now();
        final Instant expiresAt;
        final String restriction;
        final long authzVersion;
        final UUID tokenFamilyId;
        String refreshToken;
        Instant refreshExpiresAt;
        String workspace;
        boolean revoked;
        Instant revokedAt;
        Session(UUID accountId, String channel, String token, Instant expiresAt, String restriction, String workspace,
                long authzVersion, UUID tokenFamilyId) {
            this.accountId = accountId; this.channel = channel; this.token = token; this.expiresAt = expiresAt;
            this.restriction = restriction; this.workspace = workspace; this.authzVersion = authzVersion;
            this.tokenFamilyId = tokenFamilyId;
        }
    }
    static final class OrgUnit implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        String type;
        UUID parentId;
        String code;
        String name;
        String status = "ACTIVE";
        long version = 1;
    }
    static final class AuditEvent implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id;
        final String code;
        final Instant occurredAt;
        final int hashVersion;
        final UUID actor;
        final String workspace;
        final String channel;
        final String decision;
        final String outcome;
        final String requestId;
        final String reason;
        List<UUID> subjects;
        List<Map<String, Object>> resources;
        List<Map<String, Object>> changes;
        List<Map<String, Object>> evidence;
        String payloadHash;
        String previousPayloadHash;
        AuditEvent(UUID id, Instant occurredAt, int hashVersion, String code, UUID actor, String workspace, String channel, String decision,
                   String outcome, String requestId, String reason, List<UUID> subjects,
                   List<Map<String, Object>> resources, List<Map<String, Object>> changes,
                   List<Map<String, Object>> evidence, String payloadHash, String previousPayloadHash) {
            this.id = id; this.occurredAt = occurredAt; this.hashVersion = hashVersion; this.code = code; this.actor = actor; this.workspace = workspace; this.channel = channel;
            this.decision = decision; this.outcome = outcome; this.requestId = requestId; this.reason = reason;
            this.subjects = List.copyOf(subjects == null ? List.of() : subjects);
            this.resources = List.copyOf(resources == null ? List.of() : resources);
            this.changes = List.copyOf(changes == null ? List.of() : changes);
            this.evidence = List.copyOf(evidence == null ? List.of() : evidence);
            this.payloadHash = payloadHash; this.previousPayloadHash = previousPayloadHash;
        }
    }
    static final class Command implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        final String type;
        final UUID requestedBy;
        final String reason;
        final Instant createdAt = Instant.now();
        final List<UUID> targets;
        String state = "PENDING";
        Instant completedAt;
        String failureCode;
        byte[] credentialContent;
        boolean credentialDownloaded;
        Map<UUID, OneTimeCredential> credentials = new LinkedHashMap<>();
        Command(String type, UUID requestedBy, String reason, List<UUID> targets) {
            this.type = type; this.requestedBy = requestedBy; this.reason = reason; this.targets = List.copyOf(targets);
        }
    }
    static final class ImportJob implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        String state = "UPLOADED";
        String templateVersion = "v1";
        int rowCount;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        long organizationRevision = 1;
        Instant createdAt = Instant.now();
        boolean committed;
        Instant committedAt;
        byte[] credentialContent;
        boolean credentialDownloaded;
        Map<String, Map<String, Object>> roleRows = new LinkedHashMap<>();
        List<Map<String, Object>> scopeRows = new ArrayList<>();
    }
    static final class ExportJob implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        String state = "PENDING";
        String mode;
        List<UUID> targets = List.of();
        String purpose;
        int resultCount;
        Instant createdAt = Instant.now();
        Instant completedAt;
        byte[] content;
        boolean downloaded;
        boolean audit;
        Map<String, Object> filter = Map.of();
    }
    static final class ControlRequest implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        final String commandType;
        final UUID requestedBy;
        final List<UUID> targets;
        final String reason;
        final Instant requestedAt = Instant.now();
        final Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        String state = "PENDING";
        UUID decidedBy;
        Instant decidedAt;
        long version = 1;
        String roleCode;
        List<UUID> ceilingIds = List.of();
        Instant validFrom;
        Instant validTo;
        UUID assignmentId;
        UUID jobId;
        Map<UUID,Long> targetVersions = Map.of();
        String commandHash;
        long organizationRevision;
        long authorizationRevision;
        ControlRequest(String commandType, UUID requestedBy, List<UUID> targets, String reason) {
            this.commandType = commandType; this.requestedBy = requestedBy; this.targets = List.copyOf(targets); this.reason = reason;
        }
    }
    record IdempotentResult(String digest, Object response, Instant createdAt) implements Serializable {@Serial private static final long serialVersionUID=1L;}
    static final class RefreshGrant implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID familyId;
        final UUID accountId;
        final String sessionToken;
        final Instant expiresAt;
        boolean consumed;
        boolean revoked;
        RefreshGrant(UUID familyId, UUID accountId, String sessionToken, Instant expiresAt) {
            this.familyId = familyId; this.accountId = accountId; this.sessionToken = sessionToken; this.expiresAt = expiresAt;
        }
    }
    static final class DownloadTicket implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        final String secretHash;
        final UUID issuedTo;
        final String artifactType;
        final UUID artifactId;
        final Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        boolean consumed;
        DownloadTicket(String secretHash, UUID issuedTo, String artifactType, UUID artifactId) {
            this.secretHash = secretHash; this.issuedTo = issuedTo; this.artifactType = artifactType; this.artifactId = artifactId;
        }
    }
    static final class SsoTicket implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        final UUID id = UUID.randomUUID();
        final String secretHash;
        final UUID accountId;
        final String sessionToken;
        final String audience;
        final String roleCode;
        final long authorizationVersion;
        final Instant issuedAt = Instant.now();
        Instant expiresAt = Instant.now().plus(Duration.ofSeconds(60));
        boolean consumed;
        SsoTicket(String secretHash, UUID accountId, String sessionToken, String audience, String roleCode, long authorizationVersion) {
            this.secretHash = secretHash; this.accountId = accountId; this.sessionToken = sessionToken;
            this.audience = audience; this.roleCode = roleCode; this.authorizationVersion = authorizationVersion;
        }
    }
    static final class AttemptWindow implements Serializable {
        @Serial private static final long serialVersionUID=1L;
        Instant startedAt = Instant.now();
        int count;
    }
    public record DownloadPayload(byte[] content, String mediaType, String fileName) {}
    record AuditLedgerRecord(UUID id,int hashVersion,String code,Instant occurredAt,UUID actor,String workspace,String channel,String decision,String outcome,String requestId,String reason,List<UUID> subjects,List<Map<String,Object>> resources,List<Map<String,Object>> changes,List<Map<String,Object>> evidence,String payloadHash,String previousPayloadHash) {}
    /** One pending lifecycle notification for the medical-insurance adapter, built under the state lock. */
    record LifecycleProjection(UUID accountId,long authorizationVersion,Map<String,Object> payload) {}
    private record StateSnapshot(
            Map<UUID,Account> accounts,Map<String,UUID> loginIndex,Map<String,UUID> personIndex,Map<String,Session> sessions,
            Map<UUID,OrgUnit> orgUnits,Map<UUID,AuditEvent> auditEvents,Map<UUID,Command> commands,Map<UUID,ImportJob> importJobs,
            Map<UUID,ExportJob> exportJobs,Map<UUID,ExportJob> auditExportJobs,Map<UUID,ControlRequest> controls,
            Map<String,IdempotentResult> idempotency,Map<String,RefreshGrant> refreshTokens,Map<UUID,DownloadTicket> downloadTickets,
            Map<String,SsoTicket> medicalSsoTickets,Map<String,AttemptWindow> loginRate,long authzRevision,long orgRevision,byte[] previousAuditHash) implements Serializable {@Serial private static final long serialVersionUID=1L;}

    /** Serializes the domain state except the audit ledger, which production persists one row per event. */
    synchronized byte[] captureState() {
        StateSnapshot snapshot=new StateSnapshot(new HashMap<>(accounts),new HashMap<>(loginIndex),new HashMap<>(personIndex),new HashMap<>(sessions),
                new HashMap<>(orgUnits),new HashMap<>(),new HashMap<>(commands),new HashMap<>(importJobs),new HashMap<>(exportJobs),
                new HashMap<>(auditExportJobs),new HashMap<>(controls),new HashMap<>(idempotency),new HashMap<>(refreshTokens),
                new HashMap<>(downloadTickets),new HashMap<>(medicalSsoTickets),new HashMap<>(loginRate),authzRevision.get(),orgRevision.get(),new byte[32]);
        try(ByteArrayOutputStream bytes=new ByteArrayOutputStream();ObjectOutputStream output=new ObjectOutputStream(bytes)){output.writeObject(snapshot);return bytes.toByteArray();}
        catch(IOException e){throw new IllegalStateException("Unable to capture authoritative state",e);}
    }

    /** Restores the domain state. Audit events always come from the ledger; copies inside legacy checkpoints are ignored. */
    synchronized void restoreState(byte[] payload) {
        if(payload==null||payload.length==0)return;
        try(ObjectInputStream input=new ObjectInputStream(new java.io.ByteArrayInputStream(payload))){
            input.setObjectInputFilter(info->{Class<?> type=info.serialClass();if(type==null)return ObjectInputFilter.Status.UNDECIDED;String name=type.getName();return name.startsWith("com.shtong.gateway.AccountAccessService$")||name.startsWith("java.")||name.startsWith("[")?ObjectInputFilter.Status.ALLOWED:ObjectInputFilter.Status.REJECTED;});
            StateSnapshot snapshot=(StateSnapshot)input.readObject();
            restoreRequired(accounts,snapshot.accounts(),"accounts");
            restoreRequired(orgUnits,snapshot.orgUnits(),"organization units");
            restoreOptional(sessions,snapshot.sessions());restoreOptional(commands,snapshot.commands());restoreOptional(importJobs,snapshot.importJobs());
            restoreOptional(exportJobs,snapshot.exportJobs());restoreOptional(auditExportJobs,snapshot.auditExportJobs());restoreOptional(controls,snapshot.controls());restoreOptional(idempotency,snapshot.idempotency());
            restoreOptional(refreshTokens,snapshot.refreshTokens());restoreOptional(downloadTickets,snapshot.downloadTickets());restoreOptional(medicalSsoTickets,snapshot.medicalSsoTickets());restoreOptional(loginRate,snapshot.loginRate());
            normalizeRestoredState();
            authzRevision.set(Math.max(1,snapshot.authzRevision()));orgRevision.set(Math.max(1,snapshot.orgRevision()));
        }catch(IOException|ClassNotFoundException|ClassCastException e){throw new IllegalStateException("Unable to restore authoritative state",e);}
    }

    /** Empties the domain state (not the audit ledger) when no checkpoint has been committed yet. */
    synchronized void resetState() {
        for (Map<?,?> map : List.of(accounts,loginIndex,personIndex,sessions,orgUnits,commands,importJobs,exportJobs,auditExportJobs,controls,idempotency,refreshTokens,downloadTickets,medicalSsoTickets,loginRate)) map.clear();
        authzRevision.set(1);orgRevision.set(1);
    }

    private <K,V> void restoreRequired(Map<K,V> target,Map<K,V> restored,String name){if(restored==null)throw new IllegalStateException("Authoritative checkpoint is missing "+name);target.clear();target.putAll(restored);}
    private <K,V> void restoreOptional(Map<K,V> target,Map<K,V> restored){target.clear();if(restored!=null)target.putAll(restored);}
    private void normalizeRestoredState(){
        loginIndex.clear();personIndex.clear();
        for(Account account:accounts.values()){
            if(account==null||account.id==null||account.loginName==null||account.personNo==null||account.createdAt==null)throw new IllegalStateException("Authoritative checkpoint contains an incomplete account");
            if(!"DELETED".equals(account.lifecycle)&&(loginIndex.putIfAbsent(normalize(account.loginName),account.id)!=null||personIndex.putIfAbsent(normalize(account.personNo),account.id)!=null))throw new IllegalStateException("Authoritative checkpoint contains duplicate active account identity keys");
            if(account.roles==null)account.roles=new LinkedHashMap<>();if(account.passwordHistory==null)account.passwordHistory=new ArrayDeque<>();
            if(account.version<1)account.version=1;if(account.authzVersion<1)account.authzVersion=1;if(account.credentialVersion<1)account.credentialVersion=1;
            for(RoleAssignment role:account.roles.values()){if(role==null||role.id==null||role.roleCode==null)throw new IllegalStateException("Authoritative checkpoint contains an incomplete role assignment");if(role.ceilings==null)role.ceilings=new LinkedHashSet<>();if(role.status==null)role.status="ACTIVE";if(role.version<1)role.version=1;}
        }
        sessions.entrySet().removeIf(entry->{Session session=entry.getValue();return session==null||session.tokenFamilyId==null||session.refreshToken==null||session.refreshExpiresAt==null||session.authzVersion<1;});
        refreshTokens.entrySet().removeIf(entry->{RefreshGrant grant=entry.getValue();return grant==null||grant.familyId==null||grant.accountId==null||grant.sessionToken==null||grant.expiresAt==null||!sessions.containsKey(grant.sessionToken);});
        // Checkpoints written by earlier releases kept plaintext one-time credentials; drop them on load.
        for(Command command:commands.values())if(command!=null){if(command.credentials==null)command.credentials=new LinkedHashMap<>();command.credentials.clear();if(command.credentialDownloaded)command.credentialContent=null;}
        for(ImportJob job:importJobs.values())if(job!=null){if(job.rows==null)job.rows=new ArrayList<>();if(job.errors==null)job.errors=new ArrayList<>();if(job.roleRows==null)job.roleRows=new LinkedHashMap<>();if(job.scopeRows==null)job.scopeRows=new ArrayList<>();if(job.credentialDownloaded)job.credentialContent=null;}
        idempotency.replaceAll((key,value)->value==null?null:new IdempotentResult(value.digest(),withoutCredential(value.response()),value.createdAt()));
        idempotency.values().removeIf(Objects::isNull);
        for(ExportJob job:exportJobs.values())normalizeExportJob(job);for(ExportJob job:auditExportJobs.values())normalizeExportJob(job);
        for(ControlRequest control:controls.values())if(control!=null){if(control.ceilingIds==null)control.ceilingIds=List.of();if(control.targetVersions==null)control.targetVersions=Map.of();}
        medicalSsoTickets.entrySet().removeIf(entry -> {
            SsoTicket ticket = entry.getValue();
            return ticket == null || ticket.secretHash == null || ticket.accountId == null || ticket.sessionToken == null
                    || ticket.audience == null || ticket.roleCode == null || ticket.expiresAt == null;
        });
    }
    private void normalizeExportJob(ExportJob job){if(job==null)return;if(job.targets==null)job.targets=List.of();if(job.filter==null)job.filter=Map.of();}
    /** A replayed account creation reports that the one-time credential was already delivered instead of repeating it. */
    private Object withoutCredential(Object response){
        if(!(response instanceof Map<?,?> map)||!map.containsKey("credential"))return response;
        Map<String,Object> copy=new LinkedHashMap<>();map.forEach((key,value)->copy.put(String.valueOf(key),value));
        copy.remove("credential");copy.put("credentialDeliveryStatus","ALREADY_DELIVERED");return copy;
    }

    synchronized int auditMark(){return auditOrder.size();}
    synchronized List<AuditLedgerRecord> auditRecordsSince(int mark){return auditOrder.subList(mark,auditOrder.size()).stream().map(id->ledgerRecord(auditEvents.get(id))).toList();}
    synchronized List<AuditLedgerRecord> auditLedgerRecords(){return auditRecordsSince(0);}
    synchronized byte[] auditTailHash(){return previousAuditHash.clone();}

    /** Replaces the in-memory ledger with the persisted rows after verifying every hash and the complete chain. */
    synchronized void replaceAuditLedger(List<AuditLedgerRecord> records){
        String zero=HexFormat.of().formatHex(new byte[32]);Map<String,AuditLedgerRecord> byPrevious=new HashMap<>();Set<String> payloads=new HashSet<>();
        for(AuditLedgerRecord record:records){
            verifyLedgerRecord(record);
            if(!payloads.add(record.payloadHash())||byPrevious.putIfAbsent(record.previousPayloadHash(),record)!=null)throw new IllegalStateException("Audit ledger contains a duplicate or forked hash chain");
        }
        List<AuditLedgerRecord> ordered=new ArrayList<>();String current=zero;
        while(byPrevious.containsKey(current)){AuditLedgerRecord next=byPrevious.get(current);ordered.add(next);current=next.payloadHash();if(ordered.size()>records.size())throw new IllegalStateException("Audit ledger contains a cyclic hash chain");}
        if(ordered.size()!=records.size())throw new IllegalStateException("Audit ledger contains a broken hash chain");
        auditEvents.clear();auditOrder.clear();failedRequestIds.clear();previousAuditHash=new byte[32];
        for(AuditLedgerRecord record:ordered)register(eventOf(record));
    }

    /** Appends rows committed by another instance; each must extend the current chain tail. */
    synchronized void extendAuditLedger(List<AuditLedgerRecord> records){
        for(AuditLedgerRecord record:records){
            verifyLedgerRecord(record);
            if(auditEvents.containsKey(record.id())||!record.previousPayloadHash().equals(HexFormat.of().formatHex(previousAuditHash)))throw new IllegalStateException("Audit ledger rows do not extend the local chain");
            register(eventOf(record));
        }
    }

    private void verifyLedgerRecord(AuditLedgerRecord r){
        if(r.id()==null||r.code()==null||r.occurredAt()==null||r.requestId()==null||r.decision()==null||r.outcome()==null||r.payloadHash()==null||r.previousPayloadHash()==null)throw new IllegalStateException("Audit ledger contains an incomplete event");
        String expected=auditHash(r.hashVersion(),r.id(),r.code(),r.occurredAt(),r.actor(),r.workspace(),r.channel(),r.decision(),r.outcome(),r.requestId(),r.reason(),r.subjects(),r.resources(),r.changes(),r.evidence(),r.previousPayloadHash());
        if(!constantTimeEquals(expected,r.payloadHash()))throw new IllegalStateException("Audit ledger contains an event whose content does not match its hash: "+r.id());
    }
    private AuditEvent eventOf(AuditLedgerRecord r){return new AuditEvent(r.id(),r.occurredAt(),r.hashVersion(),r.code(),r.actor(),r.workspace(),r.channel(),r.decision(),r.outcome(),r.requestId(),r.reason(),r.subjects(),r.resources(),r.changes(),r.evidence(),r.payloadHash(),r.previousPayloadHash());}
    private AuditLedgerRecord ledgerRecord(AuditEvent e){return new AuditLedgerRecord(e.id,e.hashVersion==0?AUDIT_HASH_LEGACY:e.hashVersion,e.code,e.occurredAt,e.actor,e.workspace,e.channel,e.decision,e.outcome,e.requestId,e.reason,e.subjects,e.resources,e.changes,e.evidence,e.payloadHash,e.previousPayloadHash);}
    private void register(AuditEvent event){
        auditEvents.put(event.id,event);auditOrder.add(event.id);
        if("FAILED".equals(event.outcome)||"DENIED".equals(event.outcome))failedRequestIds.add(event.requestId);
        previousAuditHash=HexFormat.of().parseHex(event.payloadHash);
    }

    /**
     * Version 1 (legacy) covered only the event summary. Version 2 covers every stored field, including the
     * event id, occurrence time, resources, field changes and authorization evidence, in canonical JSON form.
     */
    String auditHash(int version,UUID id,String code,Instant occurredAt,UUID actor,String workspace,String channel,String decision,String outcome,String requestId,String reason,List<UUID> subjects,List<Map<String,Object>> resources,List<Map<String,Object>> changes,List<Map<String,Object>> evidence,String previous){
        List<UUID> safeSubjects=subjects==null?List.of():subjects;
        if(version==AUDIT_HASH_LEGACY)return sha256(String.join("|",code,String.valueOf(actor),String.valueOf(workspace),String.valueOf(channel),decision,outcome,requestId,String.valueOf(reason),safeSubjects.toString(),previous));
        if(version!=AUDIT_HASH_FULL)throw new IllegalStateException("Unsupported audit hash version "+version);
        Map<String,Object> material=new LinkedHashMap<>();
        material.put("version",version);material.put("id",id);material.put("code",code);material.put("occurredAt",occurredAt);material.put("actor",actor);
        material.put("workspace",workspace);material.put("channel",channel);material.put("decision",decision);material.put("outcome",outcome);
        material.put("requestId",requestId);material.put("reason",reason);material.put("subjects",safeSubjects);
        material.put("resources",resources==null?List.of():resources);material.put("changes",changes==null?List.of():changes);material.put("evidence",evidence==null?List.of():evidence);
        material.put("previous",previous);
        return sha256(canonicalJson(material));
    }
    static String canonicalJson(Object value){
        try{return CANONICAL_JSON.writeValueAsString(CANONICAL_JSON.convertValue(value,Object.class));}
        catch(com.fasterxml.jackson.core.JsonProcessingException|IllegalArgumentException failure){throw new IllegalStateException("Unable to canonicalize audit content",failure);}
    }

    @ReadOnlyState
    public synchronized void recordFailure(HttpServletRequest request,String errorCode,int status,String requestId){
        if(failedRequestIds.contains(requestId))return;
        String path=request.getRequestURI(),method=request.getMethod();String eventCode=failureEvent(path,method);if(eventCode==null)return;
        String token=bearerOrCookie(request);Session session=token==null?null:sessions.get(token);UUID actor=session==null?null:session.accountId;
        boolean denied=status==401||status==403;String decision=denied?"DENY":actor==null?"NOT_APPLICABLE":"ALLOW";
        append(eventCode,actor,session==null?null:session.workspace,Objects.requireNonNullElse(request.getHeader("X-Channel"),session==null?"UNKNOWN":session.channel),decision,denied?"DENIED":"FAILED",requestId,errorCode+":"+path,actor==null?List.of():List.of(actor));
    }

    private String failureEvent(String path,String method){
        if(path.endsWith("/auth/login"))return "AUTH.LOGIN.FAILED";
        if(path.endsWith("/auth/password/change"))return "AUTH.PASSWORD.CHANGE_FAILED";
        if(path.endsWith("/auth/token/refresh"))return "AUTH.TOKEN.REFRESH_FAILED";
        if(path.endsWith("/auth/logout"))return "AUTH.LOGOUT.FAILED";
        if(path.contains("/session/"))return "SESSION.CONTEXT_SWITCH_FAILED";
        if(path.contains("/profile/sensitive-fields/"))return "PROFILE.SENSITIVE_FIELD.REVEALED";
        if(path.equals("/v1/profile")||path.equals("/v1/profile/completion"))return method.equals("GET")?"PROFILE.VIEWED":"PROFILE.UPDATED";
        if(path.contains("/account-import/jobs/")&&path.endsWith("/commit"))return "ACCOUNT.IMPORT.COMMIT_FAILED";
        if(path.contains("/account-import/jobs"))return path.endsWith("/credential-ticket")?"ACCOUNT.IMPORT_RESULT.DOWNLOADED":"ACCOUNT.IMPORT_VALIDATION.FAILED";
        if(path.contains("/account-export/jobs"))return path.endsWith("/download-ticket")?"ACCOUNT.EXPORT.DOWNLOADED":"ACCOUNT.EXPORT.FAILED";
        if(path.contains("/commands/reset-password"))return "AUTH.PASSWORD.RESET";
        if(path.contains("/commands/delete"))return "ACCOUNT.DELETED";
        if(path.contains("/commands/graduate"))return "ACCOUNT.GRADUATED";
        if(path.contains("/role-assignments/")&&path.endsWith("/revoke"))return "AUTHZ.ROLE.REVOKED";
        if(path.contains("/role-assignments"))return "AUTHZ.ROLE.ASSIGNED";
        if(path.contains("/control-requests/")&&path.endsWith("/approve"))return "CONTROL.APPROVED";
        if(path.contains("/control-requests/")&&path.endsWith("/reject"))return "CONTROL.REJECTED";
        if(path.contains("/control-requests"))return path.matches(".*/control-requests/[^/]+$")?"AUDIT.DETAIL_VIEWED":"AUDIT.SEARCHED";
        if(path.contains("/org-units"))return method.equals("GET")?"ORGANIZATION.SEARCHED":method.equals("DELETE")?"ORGANIZATION.DELETED":method.equals("PATCH")?"ORGANIZATION.UPDATED":"ORGANIZATION.CREATED";
        if(path.contains("/audit/export-jobs"))return path.endsWith("/download-ticket")?"AUDIT.EXPORT.DOWNLOADED":"AUDIT.EXPORT.REQUESTED";
        if(path.contains("/audit/events/"))return "AUDIT.DETAIL_VIEWED";
        if(path.contains("/audit/events"))return "AUDIT.SEARCHED";
        if(path.matches(".*/accounts/[0-9a-fA-F-]+(/authorization)?$"))return "ACCOUNT.DETAIL_VIEWED";
        if(path.endsWith("/system/accounts"))return method.equals("GET")?"ACCOUNT.SEARCHED":"ACCOUNT.CREATED";
        return null;
    }

    public synchronized void bootstrapInitialAdmins(String firstLogin, String firstPassword, String secondLogin, String secondPassword) {
        if (!accounts.isEmpty() || firstLogin == null || secondLogin == null || firstLogin.isBlank() || secondLogin.isBlank() || firstPassword == null || secondPassword == null || firstLogin.equalsIgnoreCase(secondLogin)) return;
        for (var input : List.of(new String[]{firstLogin, firstPassword}, new String[]{secondLogin, secondPassword})) {
            Account a = new Account(); a.loginName = input[0].trim(); a.personNo = "BOOTSTRAP-" + a.loginName; a.displayName = a.loginName;
            validateNewPassword(a, input[1]); a.passwordHash = passwordEncoder.encode(input[1]); a.credentialState = "ACTIVE";
            addRole(a, "SYSTEM_ADMIN", List.of()); accounts.put(a.id, a); loginIndex.put(normalize(a.loginName), a.id); personIndex.put(normalize(a.personNo), a.id);
        }
        append("CONTROL.EXECUTED", null, "SYSTEM_ADMIN", "BOOTSTRAP", "NOT_APPLICABLE", "SUCCESS", "bootstrap", "two_admins_created", accounts.keySet().stream().toList());
    }

    public LoginResult login(String loginName,String password,String channel,String requestId){return login(loginName,password,channel,requestId,"unknown");}
    public synchronized LoginResult login(String loginName, String password, String channel, String requestId, String sourceAddress) {
        String normalized = normalize(loginName);
        String accountRateKey="account:"+normalized+":"+Objects.requireNonNullElse(channel,"");
        String sourceRateKey="source:"+normalize(sourceAddress)+":"+Objects.requireNonNullElse(channel,"");
        UUID accountId = loginIndex.get(normalized);
        Account account = accountId == null ? null : accounts.get(accountId);
        append("AUTH.LOGIN.ATTEMPTED", accountId, null, channel, "NOT_APPLICABLE", "SUCCESS", requestId, null, List.of());
        if (rateLimited(accountRateKey)||rateLimited(sourceRateKey)) {
            append("AUTH.LOGIN.FAILED", accountId, null, channel, "DENY", "DENIED", requestId, "rate_limited", accountId == null ? List.of() : List.of(accountId));
            return deniedLogin();
        }
        if(account!=null&&account.security.equals("TEMPORARILY_LOCKED")&&account.lockedUntil!=null&&!account.lockedUntil.isAfter(Instant.now())){account.security="NORMAL";account.lockedUntil=null;}
        if (account == null || account.lifecycle.equals("DELETED") || account.lifecycle.equals("GRADUATED") ||
                account.security.equals("MANUALLY_LOCKED") || account.lockedUntil != null && account.lockedUntil.isAfter(Instant.now()) ||
                account.passwordHash == null || !passwordEncoder.matches(password == null ? "" : password, account.passwordHash)) {
            registerLoginFailure(account, accountRateKey, sourceRateKey);
            String failure=account==null?"unknown_account":!account.lifecycle.equals("ACTIVE")?"inactive_account":account.security.equals("MANUALLY_LOCKED")||account.lockedUntil!=null&&account.lockedUntil.isAfter(Instant.now())?"account_locked":"invalid_credentials";
            append("AUTH.LOGIN.FAILED", accountId, null, channel, "DENY", "DENIED", requestId, failure, accountId == null ? List.of() : List.of(accountId));
            return deniedLogin();
        }
        account.failedAttemptCount = 0; account.lockedUntil = null;if(account.security.equals("TEMPORARILY_LOCKED"))account.security="NORMAL"; loginRate.remove(accountRateKey);
        boolean systemAdmin = activeRole(account, "SYSTEM_ADMIN");
        String restriction = !systemAdmin && account.credentialState.equals("TEMPORARY") ? "PASSWORD_CHANGE_ONLY" : profileRestriction(account);
        // The admin web shell is an administrative surface. When an account
        // carries both USER and an administrative identity, start that shell
        // in the highest authorized administrative context so it cannot appear
        // empty after a successful login. Mobile and user web channels retain
        // the least-privilege USER default and can explicitly switch context.
        String workspace = initialWorkspace(account, channel);
        String token = issueToken(account, channel, restriction, workspace);
        account.lastLoginAt = Instant.now(); account.updatedAt = account.lastLoginAt;
        append("AUTH.LOGIN.SUCCEEDED", account.id, workspace, channel, "ALLOW", "SUCCESS", requestId, null, List.of(account.id));
        if (restriction.equals("PASSWORD_CHANGE_ONLY")) {
            append("AUTH.PASSWORD.CHANGE_REQUIRED", account.id, workspace, channel, "ALLOW", "SUCCESS", requestId, null, List.of(account.id));
            Session session = sessions.get(token);
            return new LoginResult("PASSWORD_CHANGE_REQUIRED", null, session.expiresAt,
                    List.of("PASSWORD_CHANGE", "LOGOUT"), sessionView(session, token), token, session.refreshToken);
        }
        if (restriction.equals("PROFILE_COMPLETION_ONLY")) {
            Session session = sessions.get(token);
            return new LoginResult("PROFILE_COMPLETION_REQUIRED", null, session.expiresAt,
                    List.of("PROFILE_READ", "PROFILE_COMPLETE", "LOGOUT"), sessionView(session, token), token, session.refreshToken);
        }
        // The login name and password are the only authentication factors; every
        // workspace, including administrative ones, is established directly.
        Session session = sessions.get(token);
        return new LoginResult("AUTHENTICATED", null, null, List.of(), sessionView(session, token), token, session.refreshToken);
    }

    @ReadOnlyState
    public Principal principal(HttpServletRequest request, String requiredWorkspace, boolean allowPasswordChangeOnly, String requestId) {
        String token = bearerOrCookie(request);
        Session session = token == null ? null : sessions.get(token);
        if (session == null || session.revoked || session.expiresAt.isBefore(Instant.now())) {
            if (token != null) append("SESSION.REVOKED_TOKEN_USED", null, null, request.getHeader("X-Channel"), "DENY", "DENIED", requestId, "session_revoked", List.of());
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        Account account = accounts.get(session.accountId);
        if (account == null || !account.lifecycle.equals("ACTIVE")) {
            throw new ApiError(401, "AUTHENTICATION_REQUIRED", "error.authentication.required");
        }
        if (session.authzVersion != account.authzVersion) {
            append("SESSION.REVOKED_TOKEN_USED", account.id, session.workspace, session.channel, "DENY", "DENIED", requestId, "authorization_version_stale", List.of(account.id));
            throw new ApiError(401, "AUTHORIZATION_VERSION_STALE", "error.authorization.stale");
        }
        if (!allowPasswordChangeOnly && session.restriction.equals("PASSWORD_CHANGE_ONLY")) {
            append("AUTH.PASSWORD.CHANGE_REQUIRED", account.id, session.workspace, session.channel, "DENY", "DENIED", requestId, "restricted_session", List.of(account.id));
            throw new ApiError(401, "PASSWORD_CHANGE_REQUIRED", "error.password.changeRequired");
        }
        if (!allowPasswordChangeOnly && session.restriction.equals("PROFILE_COMPLETION_ONLY")) {
            append("PROFILE.UPDATED", account.id, session.workspace, session.channel, "DENY", "DENIED", requestId, "profile_completion_required", List.of(account.id));
            throw new ApiError(403, "PROFILE_COMPLETION_REQUIRED", "error.profile.completionRequired");
        }
        if (requiredWorkspace != null && !requiredWorkspace.equals(session.workspace)) {
            append("SESSION.CONTEXT_SWITCH_FAILED", account.id, session.workspace, session.channel, "DENY", "DENIED", requestId, "workspace_mismatch", List.of(account.id));
            throw new ApiError(403, "ACCESS_DENIED", "error.access.denied");
        }
        return new Principal(account.id, token, session.channel, session.workspace, session.restriction, account.authzVersion, session.expiresAt);
    }

    public synchronized void logout(HttpServletRequest request, String key, String requestId) {
        String token = bearerOrCookie(request);
        Session session = token == null ? null : sessions.get(token);
        String scopedKey=idempotencyKey(session==null?null:session.accountId,"logout",key);String digest="logout:"+hashToken(token);
        Boolean prior=replay(scopedKey,digest);if(Boolean.TRUE.equals(prior))return;
        if (session != null && !session.revoked) {
            revokeSession(session);
            revokeFamily(session.tokenFamilyId);
            append("AUTH.LOGOUT.SUCCEEDED", session.accountId, session.workspace, session.channel, "ALLOW", "SUCCESS", requestId, null, List.of(session.accountId));
            append("SESSION.REVOKED", session.accountId, session.workspace, session.channel, "ALLOW", "SUCCESS", requestId, "logout", List.of(session.accountId));
        } else {
            append("AUTH.LOGOUT.FAILED", null, null, request.getHeader("X-Channel"), "NOT_APPLICABLE", "FAILED", requestId, "already_revoked", List.of());
        }
        cacheIdempotent(scopedKey,digest,Boolean.TRUE);
    }

    @ReadOnlyState
    public Map<String, Object> bootstrap(HttpServletRequest request, String requestId) {
        Principal p = principal(request, null, true, requestId);
        if(p.restriction().equals("PASSWORD_CHANGE_ONLY"))throw new ApiError(401,"PASSWORD_CHANGE_REQUIRED","error.password.changeRequired");
        Account a = accounts.get(p.accountId());
        List<Map<String, Object>> workspaces = new ArrayList<>();
        if (activeRole(a,"USER")) workspaces.add(Map.of("code", "USER", "requiresStepUp", false));
        if (activeRole(a,"TEACHER_ADMIN") || activeRole(a,"STUDENT_ADMIN") || activeRole(a,"MEDICAL_INSURANCE_ADMIN")) workspaces.add(Map.of("code", "ADMIN", "requiresStepUp", false));
        if (activeRole(a,"SYSTEM_ADMIN")) workspaces.add(Map.of("code", "SYSTEM_ADMIN", "requiresStepUp", false));
        boolean profileCompletionRequired=p.restriction().equals("PROFILE_COMPLETION_ONLY");
        List<Map<String, Object>> modules = new ArrayList<>();
        if (!profileCompletionRequired && p.workspace().equals("SYSTEM_ADMIN")) {
            modules.add(linked("code", "system.accounts", "routeKey", "system.accounts", "order", 10));
            modules.add(linked("code", "system.graduates", "routeKey", "system.graduates", "order", 15));
            modules.add(linked("code", "system.authorization", "routeKey", "system.authorization", "order", 20));
            modules.add(linked("code", "system.organization", "routeKey", "system.organization", "order", 30));
            modules.add(linked("code", "system.audit", "routeKey", "system.audit", "order", 40));
        }
        if (!profileCompletionRequired && medicalInsuranceRole(a, p.workspace()) != null) {
            modules.add(linked("code", "medical-insurance", "routeKey", "medical-insurance", "order", 50,
                    "externalSystem", "_yb", "sso", "server-ticket"));
        }
        append("SESSION.CONTEXT_SWITCHED", a.id, p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, "bootstrap", List.of(a.id));
        return Map.of("contractVersion", "account-access-baseline.v1", "authorizationVersion", authzRevision.get(),"profileCompletionRequired",profileCompletionRequired,
                "activeContext", Map.of("id", sessions.get(p.token()).id, "workspace", p.workspace(), "assuranceLevel", "AAL1", "expiresAt", p.expiresAt()),
                "activeModules", List.copyOf(modules), "shellActions", profileCompletionRequired?List.of("PROFILE_COMPLETE","LOGOUT"):List.of("PROFILE", "CHANGE_PASSWORD", "LOGOUT"), "availableWorkspaces", profileCompletionRequired?List.of():workspaces);
    }

    /** Issue a short-lived, single-use server-side ticket for the medical-insurance adapter. */
    // The adapter ticket remains bound to the current parent session and workspace.
    public synchronized Map<String, Object> issueMedicalInsuranceTicket(HttpServletRequest request, String key, String requestId) {
        Principal principal = principal(request, null, false, requestId);
        String scopedKey = idempotencyKey(principal.accountId(), "medical-insurance-sso-ticket", key);
        String digest = "medical-insurance-sso-ticket:" + principal.accountId() + ":" + principal.token() + ":" + principal.workspace();
        Map<String, Object> replay = replay(scopedKey, digest);
        if (replay != null) return replay;
        Account account = accounts.get(principal.accountId());
        Session session = sessions.get(principal.token());
        String roleCode = session == null ? null : medicalInsuranceRole(account, session.workspace);
        if (roleCode == null) {
            append("MODULE.SSO.REJECTED", principal.accountId(), principal.workspace(), principal.channel(), "DENY", "DENIED", requestId, "module_role_missing", List.of(principal.accountId()));
            throw new ApiError(403, "ACCESS_DENIED", "error.access.denied");
        }
        String secret = randomToken();
        SsoTicket ticket = new SsoTicket(hashToken(secret), account.id, principal.token(), "medical-insurance", roleCode, account.authzVersion);
        medicalSsoTickets.put(ticket.secretHash, ticket);
        boolean mobileChannel = "ANDROID".equals(principal.channel()) || "IOS".equals(principal.channel());
        String entryUrl = mobileChannel && medicalInsuranceMobileEntryUrl != null && !medicalInsuranceMobileEntryUrl.isBlank()
                ? medicalInsuranceMobileEntryUrl : medicalInsuranceEntryUrl;
        Map<String, Object> response = linked(
                "moduleCode", "medical-insurance", "audience", ticket.audience, "redirectUrl", appendTicket(entryUrl, secret),
                "expiresAt", ticket.expiresAt, "singleUse", true);
        append("MODULE.SSO.TICKET_ISSUED", account.id, principal.workspace(), principal.channel(), "ALLOW", "SUCCESS", requestId, "medical-insurance", List.of(account.id));
        cacheIdempotent(scopedKey, digest, response);
        return response;
    }

    /** Exchange is restricted to the configured server-to-server adapter credentials. */
    public synchronized Map<String, Object> exchangeMedicalInsuranceTicket(String secret, String audience, String adapterId, String adapterSecret, String requestId) {
        requireSsoAdapter(adapterId, adapterSecret, requestId);
        if (!"medical-insurance".equals(audience) || secret == null || secret.isBlank() || secret.length() > 200) {
            rejectSso(requestId, "invalid_ticket_request");
            throw new ApiError(401, "SSO_TICKET_INVALID", "error.sso.ticketInvalid");
        }
        SsoTicket ticket = medicalSsoTickets.get(hashToken(secret));
        if (ticket == null || ticket.consumed || ticket.expiresAt.isBefore(Instant.now()) || !ticket.audience.equals(audience)) {
            rejectSso(requestId, "ticket_expired_or_consumed");
            throw new ApiError(401, "SSO_TICKET_INVALID", "error.sso.ticketInvalid");
        }
        Account account = accounts.get(ticket.accountId);
        Session session = sessions.get(ticket.sessionToken);
        if (account == null || session == null || session.revoked || session.expiresAt.isBefore(Instant.now()) || !account.lifecycle.equals("ACTIVE")
                || account.authzVersion != ticket.authorizationVersion || session.authzVersion != account.authzVersion
                || !ticket.roleCode.equals(medicalInsuranceRole(account, session.workspace))) {
            rejectSso(requestId, "session_or_authorization_stale");
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        ticket.consumed = true;
        append("MODULE.SSO.TICKET_EXCHANGED", account.id, session.workspace, "SSO_ADAPTER", "ALLOW", "SUCCESS", requestId, ticket.audience, List.of(account.id));
        return medicalSsoPayload(account, session, ticket.roleCode);
    }

    /** Validate the parent Shtong session on every adapter request (with adapter-side grace caching). */
    @ReadOnlyState
    public synchronized Map<String, Object> introspectMedicalInsuranceSession(String sessionToken, String adapterId, String adapterSecret, String requestId) {
        requireSsoAdapter(adapterId, adapterSecret, requestId);
        Session session = sessionToken == null ? null : sessions.get(sessionToken);
        Account account = session == null ? null : accounts.get(session.accountId);
        String roleCode = account == null || session == null ? null : medicalInsuranceRole(account, session.workspace);
        // Graduation revokes all parent sessions immediately. Allow one
        // read-only projection so the adapter can mirror the terminal state;
        // the adapter rejects module access after persisting it.
        boolean graduatedProjection = account != null && "GRADUATED".equals(account.lifecycle);
        if (session == null || account == null || session.expiresAt.isBefore(Instant.now())
                || (!graduatedProjection && session.revoked)
                || (!graduatedProjection && !account.lifecycle.equals("ACTIVE"))
                || (!graduatedProjection && session.authzVersion != account.authzVersion)
                || (!graduatedProjection && roleCode == null)) {
            rejectSso(requestId, "session_inactive");
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        append("MODULE.SSO.SESSION_INTROSPECTED", account.id, session.workspace, "SSO_ADAPTER", "ALLOW", "SUCCESS", requestId, "medical-insurance", List.of(account.id));
        return medicalSsoPayload(account, session, graduatedProjection ? "USER" : roleCode);
    }

    public synchronized Map<String, Object> revokeMedicalInsuranceSession(String sessionToken, String adapterId, String adapterSecret, String requestId) {
        requireSsoAdapter(adapterId, adapterSecret, requestId);
        Session session = sessionToken == null ? null : sessions.get(sessionToken);
        if (session != null && !session.revoked) {
            revokeSession(session);
            revokeFamily(session.tokenFamilyId);
            append("MODULE.SSO.SESSION_REVOKED", session.accountId, session.workspace, "SSO_ADAPTER", "ALLOW", "SUCCESS", requestId, "medical-insurance_logout", List.of(session.accountId));
            append("AUTH.LOGOUT.SUCCEEDED", session.accountId, session.workspace, "SSO_ADAPTER", "ALLOW", "SUCCESS", requestId, "medical-insurance_logout", List.of(session.accountId));
        }
        return linked("revoked", true);
    }

    public synchronized Map<String, Object> switchContext(HttpServletRequest request, String workspace, String key, String requestId) {
        Principal p = principal(request, null, false, requestId);
        String scopedKey=idempotencyKey(p.accountId(),"session-context",key);String digest="context:"+workspace;Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        Account a = accounts.get(p.accountId());
        boolean permitted = workspace.equals("USER") && activeRole(a,"USER") || workspace.equals("ADMIN") && (activeRole(a,"TEACHER_ADMIN") || activeRole(a,"STUDENT_ADMIN") || activeRole(a,"MEDICAL_INSURANCE_ADMIN")) || workspace.equals("SYSTEM_ADMIN") && activeRole(a,"SYSTEM_ADMIN");
        if (!permitted) { append("SESSION.CONTEXT_SWITCH_FAILED", a.id, p.workspace(), p.channel(), "DENY", "DENIED", requestId, "role_missing", List.of(a.id)); throw new ApiError(403, "ACCESS_DENIED", "error.access.denied"); }
        Session s = sessions.get(p.token());
        s.workspace = workspace;
        append("SESSION.CONTEXT_SWITCHED", a.id, workspace, p.channel(), "ALLOW", "SUCCESS", requestId, null, List.of(a.id));
        Map<String,Object> response=sessionView(s,s.token);cacheIdempotent(scopedKey,digest,response);return response;
    }

    @ReadOnlyState
    public Map<String, Object> getProfile(HttpServletRequest req, String requestId) {
        Principal p = profileCompletionPrincipal(req,requestId); Account a = accounts.get(p.accountId());
        append("PROFILE.VIEWED", a.id, p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, null, List.of(a.id));
        return profileView(a);
    }

    public synchronized Map<String, Object> changePassword(HttpServletRequest req, String currentPassword, String newPassword, String key, String requestId) {
        String originalToken=bearerOrCookie(req);Session original=originalToken==null?null:sessions.get(originalToken);String scopedKey=idempotencyKey(original==null?null:original.accountId,"password-change",key);String digest="password-change:"+sha256(Objects.requireNonNullElse(currentPassword,""))+":"+sha256(Objects.requireNonNullElse(newPassword,""));Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        Principal p = principal(req, null, true, requestId); Account a = accounts.get(p.accountId());
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, a.passwordHash)) throw new ApiError(400, "VALIDATION_FAILED", "error.password.policy");
        validateNewPassword(a, newPassword);
        rememberPassword(a, a.passwordHash);
        a.passwordHash = passwordEncoder.encode(newPassword); a.credentialState = "ACTIVE"; a.credentialVersion++; a.version++;
        revokeAll(a.id, "password_changed");
        String token = issueToken(a, p.channel(), profileRestriction(a), initialWorkspace(a, p.channel()));
        Session replacement = sessions.get(token);
        append("AUTH.PASSWORD.CHANGED", a.id, replacement.workspace, p.channel(), "ALLOW", "SUCCESS", requestId, "changed", List.of(a.id));
        Map<String,Object> response=sessionView(replacement, token);response.put("_transportToken",replacement.token);response.put("_transportRefreshToken",replacement.refreshToken);cacheIdempotent(scopedKey,digest,response);return response;
    }

    @ReadOnlyState
    public List<Map<String,Object>> administrativeDivisions(HttpServletRequest req,String level,String parentCode,String requestId) {
        profileCompletionPrincipal(req,requestId);
        return divisionService().list(level,parentCode).stream().map(item->Map.<String,Object>of("code",item.code(),"name",item.name())).toList();
    }

    public synchronized Map<String,Object> completeProfile(HttpServletRequest req,String idCardNumber,String mobileNumber,
            Map<String,String> currentAddress,Map<String,String> householdAddress,String key,String requestId) {
        return completeProfile(req, idCardNumber, mobileNumber, "汉族", currentAddress, householdAddress, key, requestId);
    }

    public synchronized Map<String,Object> completeProfile(HttpServletRequest req,String idCardNumber,String mobileNumber,
            String ethnicity, Map<String,String> currentAddress,Map<String,String> householdAddress,String key,String requestId) {
        String originalToken=bearerOrCookie(req);Session original=originalToken==null?null:sessions.get(originalToken);String normalizedId=normalizeIdCard(idCardNumber),normalizedMobile=Objects.requireNonNullElse(mobileNumber,"").trim();
        String normalizedEthnicity=normalizeEthnicity(ethnicity);
        String digest="profile-complete:"+sha256(normalizedId)+":"+sha256(normalizedMobile)+":"+normalizedEthnicity+":"+sha256(canonical(currentAddress))+":"+sha256(canonical(householdAddress));
        String scopedKey=idempotencyKey(original==null?null:original.accountId,"profile-completion",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        Principal p=principal(req,null,true,requestId);Account a=accounts.get(p.accountId());
        if(!activeRole(a,"USER"))throw new ApiError(403,"ACCESS_DENIED","error.access.denied");
        if(a.credentialState.equals("TEMPORARY"))throw new ApiError(401,"PASSWORD_CHANGE_REQUIRED","error.password.changeRequired");
        if(a.profileCompletedAt!=null)throw new ApiError(409,"PROFILE_ALREADY_COMPLETED","error.profile.alreadyCompleted");
        List<ApiError.FieldError> fieldErrors=new ArrayList<>();if(!validIdCard(normalizedId))fieldErrors.add(new ApiError.FieldError("idCardNumber","INVALID"));if(!MOBILE.matcher(normalizedMobile).matches())fieldErrors.add(new ApiError.FieldError("mobileNumber","INVALID"));if(!fieldErrors.isEmpty())throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed",fieldErrors);
        if(accounts.values().stream().anyMatch(other->!other.id.equals(a.id)&&!"DELETED".equals(other.lifecycle)&&normalizedId.equals(other.idCardNumber)))throw new ApiError(409,"DUPLICATE_ID_CARD","error.profile.duplicateIdCard");
        Address current=resolveAddress(currentAddress),household=resolveAddress(householdAddress);
        a.idCardNumber=normalizedId;a.mobileNumber=normalizedMobile;a.ethnicity=normalizedEthnicity;a.currentAddress=current;a.householdAddress=household;a.profileCompletedAt=Instant.now();a.version++;a.updatedAt=Instant.now();
        Session old=sessions.get(p.token());revokeAll(a.id,"profile_completed");String token=issueToken(a,p.channel(),"NORMAL",old==null?initialWorkspace(a,p.channel()):old.workspace);Session replacement=sessions.get(token);
        List<Map<String,Object>> changes=List.of(sensitiveChange("idCardNumber"),sensitiveChange("mobileNumber"),sensitiveChange("currentAddress"),sensitiveChange("householdAddress"));
        appendWithChanges("PROFILE.UPDATED",p,a,requestId,"profile_completed",changes);
        Map<String,Object> response=new LinkedHashMap<>();response.put("profile",profileView(a));response.put("session",sessionView(replacement,token));response.put("_transportToken",replacement.token);response.put("_transportRefreshToken",replacement.refreshToken);cacheIdempotent(scopedKey,digest,response);return response;
    }

    public Map<String,Object> revealSensitiveProfileField(HttpServletRequest req,String field,String password,String requestId) {
        Principal p=principal(req,null,false,requestId);Account a=accounts.get(p.accountId());String normalized=Objects.requireNonNullElse(field,"").trim().toUpperCase(Locale.ROOT);
        if(!SENSITIVE_PROFILE_FIELDS.contains(normalized)||a.profileCompletedAt==null)throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");
        String rateKey="profile-reveal:"+a.id;
        if(rateLimited(rateKey))throw new ApiError(429,"RATE_LIMITED","error.rateLimited");
        if(!passwordEncoder.matches(Objects.requireNonNullElse(password,""),a.passwordHash)){
            registerLoginFailure(null,rateKey);append("PROFILE.SENSITIVE_FIELD.REVEALED",a.id,p.workspace(),p.channel(),"DENY","DENIED",requestId,"password_verification_failed",List.of(a.id));
            throw new ApiError(403,"SENSITIVE_REAUTHENTICATION_FAILED","error.profile.reauthenticationFailed");
        }
        loginRate.remove(rateKey);String value=switch(normalized){case "ID_CARD_NUMBER"->a.idCardNumber;case "MOBILE_NUMBER"->a.mobileNumber;case "CURRENT_ADDRESS"->fullAddress(a.currentAddress);case "HOUSEHOLD_ADDRESS"->fullAddress(a.householdAddress);default->throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");};
        append("PROFILE.SENSITIVE_FIELD.REVEALED",a.id,p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"field:"+normalized,List.of(a.id));
        return Map.of("field",normalized,"value",value);
    }

    @ReadOnlyState
    public Map<String, Object> listAccounts(HttpServletRequest req, Map<String, String> filters, String requestId) {
        Principal p = requireSystem(req, requestId);
        String lifecycle = filters.getOrDefault("lifecycleStatus", "ACTIVE");
        if (lifecycle.isBlank()) lifecycle = "ACTIVE";
        if (!Set.of("ACTIVE", "GRADUATED", "DELETED").contains(lifecycle)) throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");
        final String selectedLifecycle = lifecycle;
        List<Account> result = accounts.values().stream().filter(a -> a.lifecycle.equals(selectedLifecycle))
                .filter(a -> filters.get("q") == null || (a.loginName + a.displayName + a.personNo).toLowerCase(Locale.ROOT).contains(filters.get("q").toLowerCase(Locale.ROOT)))
                .filter(a -> filters.get("securityStatus") == null || a.security.equals(filters.get("securityStatus")))
                .filter(a -> filters.get("roleCode") == null || activeRole(a,filters.get("roleCode")))
                .filter(a -> filters.get("passwordChangeRequired") == null || Boolean.parseBoolean(filters.get("passwordChangeRequired")) == a.credentialState.equals("TEMPORARY"))
                .filter(a -> filters.get("orgUnitId") == null || accountWithinOrg(a,parseUuid(filters.get("orgUnitId"))))
                .sorted(Comparator.comparing((Account a) -> a.createdAt).thenComparing(a -> a.id)).toList();
        append("ACCOUNT.SEARCHED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, null, result.stream().map(a -> a.id).toList());
        Cursor after = decodeStableCursor(filters.get("cursor")); int limit = Math.min(200, Math.max(1, parseInt(filters.get("limit"), 50)));
        List<Account> remaining=result.stream().filter(a->after==null||a.createdAt.isAfter(after.createdAt())||a.createdAt.equals(after.createdAt())&&a.id.compareTo(after.id())>0).toList();
        List<Account> page = remaining.stream().limit(limit).toList();boolean hasMore=remaining.size()>page.size();
        return linked("items", page.stream().map(this::summary).toList(), "nextCursor", hasMore?encodeCursor(page.getLast()):null, "hasMore", hasMore);
    }

    @ReadOnlyState
    public Map<String, Object> accountDetail(HttpServletRequest req, UUID id, String requestId) {
        Principal p = requireSystem(req, requestId); Account a = accounts.get(id); if (a == null) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");
        append("ACCOUNT.DETAIL_VIEWED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, null, List.of(id));
        return accountView(a);
    }

    public synchronized Map<String, Object> createAccount(HttpServletRequest req, String loginName, String displayName, String personNo, String gender, UUID classId, List<Map<String, Object>> roleAssignments, String reason, String idempotencyKey, String requestId) {
        Principal p = requireSystem(req, requestId); String normalizedGender=normalizeGender(gender);String digest="create:"+normalize(loginName)+":"+displayName+":"+normalize(personNo)+":"+normalizedGender+":"+classId+":"+String.valueOf(roleAssignments);String scopedKey=idempotencyKey(p.accountId(),"account-create",idempotencyKey);
        Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        if (loginName == null || !LOGIN.matcher(loginName).matches() || displayName == null || displayName.isBlank() || personNo == null || personNo.isBlank()) throw new ApiError(400, "VALIDATION_FAILED", "error.validation.failed");
        if (loginIndex.containsKey(normalize(loginName)) || personIndex.containsKey(normalize(personNo))) throw new ApiError(409, "DUPLICATE", "error.account.duplicate");
        Account a = new Account(); a.loginName = loginName.trim(); a.displayName = displayName.trim(); a.personNo = personNo.trim(); a.gender = normalizedGender; a.classId = classId;
        String temp = generateTemporaryPassword(); a.passwordHash = passwordEncoder.encode(temp);
        if (roleAssignments == null || roleAssignments.isEmpty()) throw new ApiError(400, "VALIDATION_FAILED", "error.validation.failed");
        for (Map<String, Object> input : roleAssignments) { String role=String.valueOf(input.get("roleCode")); if(!INITIAL_ROLES.contains(role))throw new ApiError(422,"ROLE_SCOPE_TYPE_INVALID","error.role.invalid"); addRole(a, role, ids(input.get("scopeCeilings"))); }
        OrgUnit accountClass=classId==null?null:orgUnits.get(classId);if(classId!=null&&(accountClass==null||!accountClass.status.equals("ACTIVE")||!accountClass.type.equals("CLASS")))throw new ApiError(422,"VALIDATION_FAILED","error.account.classInvalid");
        if (activeRole(a,"USER") && accountClass == null) throw new ApiError(422, "VALIDATION_FAILED", "error.account.classRequired");
        if (!activeRole(a,"USER") && normalizedGender != null) throw new ApiError(422, "VALIDATION_FAILED", "error.account.genderNotApplicable");
        accounts.put(a.id, a); loginIndex.put(normalize(a.loginName), a.id); personIndex.put(normalize(a.personNo), a.id);
        append("ACCOUNT.CREATED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, reason, List.of(a.id));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("account", accountView(a)); result.put("credentialDeliveryStatus", "AVAILABLE_ONCE"); result.put("credential", Map.of("temporaryPassword", temp)); cacheIdempotent(scopedKey,digest,withoutCredential(result)); return result;
    }

    public synchronized Map<String, Object> updateAccount(HttpServletRequest req, UUID id, String loginName, String displayName, String gender,String idCardNumber,String mobileNumber,Map<String,String> currentAddress,Map<String,String> householdAddress,long expectedVersion, String reason, String key, String requestId) {
        return updateAccount(req,id,loginName,displayName,gender,null,idCardNumber,mobileNumber,currentAddress,householdAddress,expectedVersion,reason,key,requestId);
    }

    public synchronized Map<String, Object> updateAccount(HttpServletRequest req, UUID id, String loginName, String displayName, String gender,String ethnicity,String idCardNumber,String mobileNumber,Map<String,String> currentAddress,Map<String,String> householdAddress,long expectedVersion, String reason, String key, String requestId) {
        Principal p=requireSystem(req,requestId);String normalizedGender=normalizeGender(gender),normalizedEthnicity=ethnicity==null?null:normalizeEthnicity(ethnicity),normalizedId=idCardNumber==null?null:normalizeIdCard(idCardNumber),normalizedMobile=mobileNumber==null?null:mobileNumber.trim();String digest="account-update:"+id+":"+sha256(Objects.toString(loginName,""))+":"+sha256(Objects.toString(displayName,""))+":"+normalizedGender+":"+normalizedEthnicity+":"+sha256(Objects.toString(normalizedId,""))+":"+sha256(Objects.toString(normalizedMobile,""))+":"+sha256(canonical(currentAddress))+":"+sha256(canonical(householdAddress))+":"+expectedVersion+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"account-update",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        Account a=accounts.get(id);if(a==null||a.lifecycle.equals("DELETED"))throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");
        if(a.version!=expectedVersion)throw new ApiError(409,"VERSION_CONFLICT","error.version.conflict");
        if(loginName==null||!LOGIN.matcher(loginName).matches()||displayName==null||displayName.isBlank()||displayName.length()>100)throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");
        if(!activeRole(a,"USER")&&normalizedGender!=null)throw new ApiError(422,"VALIDATION_FAILED","error.account.genderNotApplicable");
        if(!activeRole(a,"USER")&&normalizedEthnicity!=null)throw new ApiError(422,"VALIDATION_FAILED","error.account.ethnicityNotApplicable");
        boolean sensitiveSupplied=normalizedId!=null||normalizedMobile!=null||currentAddress!=null||householdAddress!=null;Address nextCurrent=null,nextHousehold=null;
        if(sensitiveSupplied){if(!activeRole(a,"USER")||a.profileCompletedAt==null||normalizedId==null||normalizedMobile==null||currentAddress==null||householdAddress==null)throw new ApiError(422,"VALIDATION_FAILED","error.validation.failed");if(!validIdCard(normalizedId)||!MOBILE.matcher(normalizedMobile).matches())throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");if(accounts.values().stream().anyMatch(other->!other.id.equals(a.id)&&!"DELETED".equals(other.lifecycle)&&normalizedId.equals(other.idCardNumber)))throw new ApiError(409,"DUPLICATE_ID_CARD","error.profile.duplicateIdCard");nextCurrent=resolveAddress(currentAddress);nextHousehold=resolveAddress(householdAddress);}
        String nextLogin=loginName.trim(),nextDisplay=displayName.trim();UUID duplicate=loginIndex.get(normalize(nextLogin));if(duplicate!=null&&!duplicate.equals(a.id))throw new ApiError(409,"DUPLICATE_LOGIN_NAME","error.account.duplicateLoginName");
        List<Map<String,Object>> changes=new ArrayList<>();
        if(!a.loginName.equals(nextLogin))changes.add(change("loginName",a.loginName,nextLogin));
        if(!a.displayName.equals(nextDisplay))changes.add(change("displayName",a.displayName,nextDisplay));
        if(!Objects.equals(a.gender,normalizedGender))changes.add(change("gender",a.gender,normalizedGender));
        if(normalizedEthnicity!=null&&!Objects.equals(a.ethnicity,normalizedEthnicity))changes.add(change("ethnicity",a.ethnicity,normalizedEthnicity));
        if(sensitiveSupplied&&!Objects.equals(a.idCardNumber,normalizedId))changes.add(sensitiveUpdate("idCardNumber",a.idCardNumber!=null));
        if(sensitiveSupplied&&!Objects.equals(a.mobileNumber,normalizedMobile))changes.add(sensitiveUpdate("mobileNumber",a.mobileNumber!=null));
        if(sensitiveSupplied&&!sameAddress(a.currentAddress,nextCurrent))changes.add(sensitiveUpdate("currentAddress",a.currentAddress!=null));
        if(sensitiveSupplied&&!sameAddress(a.householdAddress,nextHousehold))changes.add(sensitiveUpdate("householdAddress",a.householdAddress!=null));
        if(changes.isEmpty())throw new ApiError(400,"VALIDATION_FAILED","error.validation.noChanges");
        boolean loginChanged=!a.loginName.equals(nextLogin);if(loginChanged){loginIndex.remove(normalize(a.loginName));loginIndex.put(normalize(nextLogin),a.id);a.loginName=nextLogin;}
        a.displayName=nextDisplay;a.gender=normalizedGender;if(normalizedEthnicity!=null)a.ethnicity=normalizedEthnicity;if(sensitiveSupplied){a.idCardNumber=normalizedId;a.mobileNumber=normalizedMobile;a.currentAddress=nextCurrent;a.householdAddress=nextHousehold;}a.version++;a.updatedAt=Instant.now();if(loginChanged)revokeAll(a.id,"login_name_changed");
        appendWithChanges("ACCOUNT.UPDATED",p,a,requestId,reason,changes);Map<String,Object> response=accountView(a);cacheIdempotent(scopedKey,digest,response);return response;
    }

    public synchronized Map<String, Object> changeEnrollment(HttpServletRequest req, UUID id, UUID classId, long expectedVersion, String reason, String key, String requestId) {
        Principal p=requireSystem(req,requestId);String digest="enrollment-change:"+id+":"+classId+":"+expectedVersion+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"account-enrollment-change",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        Account a=accounts.get(id);if(a==null||!a.lifecycle.equals("ACTIVE"))throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");
        if(!activeRole(a,"USER"))throw new ApiError(422,"VALIDATION_FAILED","error.account.studentRequired");
        if(a.version!=expectedVersion)throw new ApiError(409,"VERSION_CONFLICT","error.version.conflict");
        OrgUnit target=orgUnits.get(classId);if(target==null||!target.status.equals("ACTIVE")||!target.type.equals("CLASS"))throw new ApiError(422,"VALIDATION_FAILED","error.account.classInvalid");
        if(Objects.equals(a.classId,classId))throw new ApiError(400,"VALIDATION_FAILED","error.validation.noChanges");
        UUID before=a.classId;String beforePath=before==null||!orgUnits.containsKey(before)?null:orgPath(orgUnits.get(before));a.classId=classId;a.version++;a.authzVersion++;a.updatedAt=Instant.now();authzRevision.incrementAndGet();revokeAll(a.id,"enrollment_changed");
        List<Map<String,Object>> changes=List.of(change("classId",before,classId),change("organizationPath",beforePath,orgPath(target)));appendWithChanges("ACCOUNT.ENROLLMENT_CHANGED",p,a,requestId,reason,changes);Map<String,Object> response=accountView(a);cacheIdempotent(scopedKey,digest,response);return response;
    }

    public synchronized Map<String, Object> command(HttpServletRequest req, String type, List<UUID> ids, int expectedCount, String reason, String key, String requestId) {
        Principal p = requireSystem(req, requestId); validateTargetIds(ids, expectedCount);String digest=type+":"+ids.stream().sorted().toList()+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"account-command-"+type.toLowerCase(Locale.ROOT),key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        List<UUID> sorted = ids.stream().distinct().sorted().toList(); List<Account> targets = sorted.stream().map(accounts::get).toList();
        if (targets.stream().anyMatch(Objects::isNull)) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");
        if(targets.stream().anyMatch(a->!a.lifecycle.equals("ACTIVE")))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.account.inactive");
        if (targets.stream().anyMatch(a -> a.id.equals(p.accountId()))) throw new ApiError(409, "SELF_OPERATION_FORBIDDEN", "error.account.selfOperation");
        if (type.equals("GRADUATE") && targets.stream().anyMatch(a -> a.roles.values().stream().anyMatch(r -> activeRole(a,r.roleCode)&&!r.roleCode.equals("USER")))) throw new ApiError(422, "GRADUATE_ROLE_CONFLICT", "error.account.graduateRoleConflict");
        if (type.equals("DELETE") && targets.stream().anyMatch(a -> activeRole(a,"SYSTEM_ADMIN")) && accounts.values().stream().filter(a -> a.lifecycle.equals("ACTIVE") && activeRole(a,"SYSTEM_ADMIN")).count() <= targets.stream().filter(a -> activeRole(a,"SYSTEM_ADMIN")).count()) throw new ApiError(409, "LAST_SYSTEM_ADMIN_PROTECTED", "error.lastSystemAdmin");
        Command c = new Command(type, p.accountId(), reason, sorted); commands.put(c.id, c);
        boolean controlled = type.equals("RESET") && targets.stream().anyMatch(a->activeRole(a,"SYSTEM_ADMIN")) || type.equals("DELETE");
        if (controlled) {
            ControlRequest control = new ControlRequest(type.equals("RESET")?"RESET_SYSTEM_ADMIN":"DELETE_ACCOUNTS", p.accountId(), sorted, reason); control.jobId=c.id;control.commandHash=sha256(digest);control.organizationRevision=orgRevision.get();control.authorizationRevision=authzRevision.get();control.targetVersions=targetVersions(sorted);controls.put(control.id, control);
            append("CONTROL.REQUESTED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, reason, sorted);
            Map<String,Object> response=linked("jobId", c.id, "state", "PENDING_APPROVAL", "controlRequestId", control.id);cacheIdempotent(scopedKey,digest,response);return response;
        }
        executeCommand(c,p,requestId);Map<String,Object> response=linked("jobId", c.id, "state", c.state, "controlRequestId", null);cacheIdempotent(scopedKey,digest,response);return response;
    }

    @ReadOnlyState
    public Map<String, Object> commandStatus(HttpServletRequest req, UUID id, String requestId) {
        Principal p = requireSystem(req, requestId); Command c = commands.get(id); if (c == null) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");
        return linked("id", c.id, "state", c.state, "resultCount", c.targets.size(), "failureCode", c.failureCode, "createdAt", c.createdAt, "completedAt", c.completedAt);
    }

    @ReadOnlyState
    public List<Map<String, Object>> roles(HttpServletRequest req, String requestId) {
        Principal p=requireSystem(req, requestId);append("ACCOUNT.SEARCHED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"role_catalog",List.of()); return List.of(
                Map.of("code", "USER", "workspace", "USER", "allowedScopeCeilingTypes", List.of()),
                Map.of("code", "TEACHER_ADMIN", "workspace", "ADMIN", "allowedScopeCeilingTypes", List.of("GRADE")),
                Map.of("code", "STUDENT_ADMIN", "workspace", "ADMIN", "allowedScopeCeilingTypes", List.of("COLLEGE", "GRADE", "CLASS")),
                Map.of("code", "MEDICAL_INSURANCE_ADMIN", "workspace", "ADMIN", "allowedScopeCeilingTypes", List.of("COLLEGE", "GRADE", "CLASS")),
                Map.of("code", "SYSTEM_ADMIN", "workspace", "SYSTEM_ADMIN", "allowedScopeCeilingTypes", List.of()));
    }

    @ReadOnlyState
    public Map<String, Object> authorization(HttpServletRequest req, UUID id, String requestId) {
        Principal p=requireSystem(req, requestId); Account a = accounts.get(id); if (a == null) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");append("ACCOUNT.DETAIL_VIEWED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"authorization",List.of(id));
        return Map.of("accountId", a.id, "authorizationVersion", a.authzVersion, "roleAssignments", a.roles.values().stream().map(r -> roleView(r)).toList());
    }
    @ReadOnlyState
    public List<Map<String, Object>> assignments(HttpServletRequest req, UUID id, String requestId) {
        Principal p=requireSystem(req, requestId); Account a = accounts.get(id); if (a == null) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");append("ACCOUNT.DETAIL_VIEWED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"role_assignments",List.of(id)); return a.roles.values().stream().map(this::roleView).toList();
    }
    public synchronized Map<String, Object> createAssignment(HttpServletRequest req, UUID id, String roleCode, List<UUID> ceilingIds, Instant validFrom, Instant validTo, String reason, String key, String requestId) {
        Principal p = requireSystem(req, requestId);String digest="assignment-create:"+id+":"+roleCode+":"+ceilingIds+":"+validFrom+":"+validTo+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"role-assignment-create",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay; Account a = accounts.get(id); if (a == null) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");if(!a.lifecycle.equals("ACTIVE"))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.account.inactive");if(validFrom.isBefore(Instant.now().minusSeconds(60))||validTo!=null&&!validTo.isAfter(validFrom))throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");
        if (id.equals(p.accountId())) throw new ApiError(403, "ACCESS_DENIED", "error.access.denied");
        if (roleCode.equals("SYSTEM_ADMIN")) {
            validateRoleAssignment(a,roleCode,ceilingIds);
            ControlRequest c = new ControlRequest("ASSIGN_SYSTEM_ADMIN", p.accountId(), List.of(a.id), reason); c.roleCode = roleCode; c.ceilingIds = List.copyOf(ceilingIds);c.validFrom=validFrom;c.validTo=validTo;c.commandHash=sha256("assign-system:"+id+":"+ceilingIds+":"+validFrom+":"+validTo+":"+reason);c.organizationRevision=orgRevision.get();c.authorizationRevision=authzRevision.get();c.targetVersions=targetVersions(c.targets); controls.put(c.id, c);
            append("CONTROL.REQUESTED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, reason, List.of(a.id));
            Map<String,Object> response=linked("state", "PENDING_APPROVAL", "controlRequestId", c.id, "expiresAt", c.expiresAt);cacheIdempotent(scopedKey,digest,response);return response;
        }
        RoleAssignment created=addRole(a, roleCode, ceilingIds);created.validFrom=validFrom;created.validTo=validTo; a.authzVersion++; a.version++;authzRevision.incrementAndGet(); revokeAll(a.id, "authorization_changed"); append("AUTHZ.ROLE.ASSIGNED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, reason, List.of(a.id));Map<String,Object> response=roleView(created);cacheIdempotent(scopedKey,digest,response);return response;
    }
    public synchronized Map<String, Object> revokeAssignment(HttpServletRequest req, UUID assignmentId, String reason, String key, String requestId) {
        Principal p = requireSystem(req, requestId);String digest="assignment-revoke:"+assignmentId+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"role-assignment-revoke",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay; Account account = accounts.values().stream().filter(a -> a.roles.values().stream().anyMatch(r -> r.id.equals(assignmentId))).findFirst().orElseThrow(() -> new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound"));if(account.id.equals(p.accountId()))throw new ApiError(403,"ACCESS_DENIED","error.access.denied");
        RoleAssignment r = account.roles.values().stream().filter(x -> x.id.equals(assignmentId)).findFirst().orElseThrow();if(!isRoleActive(r))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");
        if (r.roleCode.equals("SYSTEM_ADMIN")) { ControlRequest c=new ControlRequest("REVOKE_SYSTEM_ADMIN",p.accountId(),List.of(account.id),reason);c.assignmentId=assignmentId;c.commandHash=sha256("revoke-system:"+assignmentId+":"+reason);c.organizationRevision=orgRevision.get();c.authorizationRevision=authzRevision.get();c.targetVersions=targetVersions(c.targets);controls.put(c.id,c);append("CONTROL.REQUESTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(account.id));Map<String,Object> response=linked("state","PENDING_APPROVAL","controlRequestId",c.id,"expiresAt",c.expiresAt);cacheIdempotent(scopedKey,digest,response);return response; }
        r.status = "REVOKED";r.version++; account.authzVersion++;authzRevision.incrementAndGet(); revokeAll(account.id, "role_revoked"); append("AUTHZ.ROLE.REVOKED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, reason, List.of(account.id));Map<String,Object> response=roleView(r);cacheIdempotent(scopedKey,digest,response);return response;
    }

    @ReadOnlyState
    public synchronized byte[] template(HttpServletRequest req, String requestId) {
        requireSystem(req, requestId);
        try (Workbook workbook = new XSSFWorkbook()) {
            ((XSSFWorkbook)workbook).getProperties().getCustomProperties().addProperty("templateVersion", Presentation.template("version"));
            Sheet sheet = workbook.createSheet(Presentation.template("sheet"));
            writeRow(sheet, 0, Presentation.headers());
            Sheet instructions = workbook.createSheet("填写说明");
            List<List<String>> notes = List.of(
                List.of("项目", "填写要求"),
                List.of("填写位置", "只在账户名单第二行起填写，一人一行；不要更改表头。一次最多5000人，文件不超过10MB。"),
                List.of("登录账号", "必填，1至100位文字、数字、点、下划线或短横线；不能与已有账号重复。"),
                List.of("姓名、学工号", "必填，姓名可以重复，学工号不能重复且创建后不可修改。所有列均按文本填写，保留学工号前导零。"),
                List.of("性别", "选填，仅学生填写；从男、女、其他中选择。教师或仅承担管理工作的账户留空。"),
                List.of("所属班级", "普通用户必填；从组织对照复制完整名称或编号。其他身份可留空。"),
                List.of("身份", "选择普通用户、教师/辅导员或学生管理员；每人先导入一个身份，其他身份可在页面添加。"),
                List.of("管理范围", "教师/辅导员填写年级；学生管理员填写学院、年级或班级；普通用户留空。多个范围用分号分隔。"),
                List.of("组织名称", "可使用完整名称、组织编号或唯一的名称；同名组织必须填写完整名称或编号。先在学校与班级中建好组织。"),
                List.of("普通用户示例（勿复制为真实数据）", "student001；张同学；20260001；女；学校 / 学院 / 2026级 / 一班；普通用户；管理范围留空"),
                List.of("教师示例（勿复制为真实数据）", "teacher001；李老师；T0001；性别留空；所属班级留空；教师/辅导员；学校 / 学院 / 2026级"),
                List.of("安全要求", "不要包含公式、外部链接或隐藏数据。上传检查通过后，仍需点击确认导入。初始密码文件仅能下载一次，请妥善保管。"));
            for (int i = 0; i < notes.size(); i++) writeRow(instructions, i, notes.get(i));
            Sheet organizations = workbook.createSheet("组织对照");
            writeRow(organizations, 0, List.of("完整名称", "组织编号", "类型", "可选班级"));
            int index = 1, classIndex = 1;
            for (OrgUnit unit : orgUnits.values().stream().filter(o -> o.status.equals("ACTIVE")).sorted(Comparator.comparing(this::orgPath)).toList()) {
                writeRow(organizations, index++, List.of(orgPath(unit), unit.code, Presentation.label(unit.type)));
                if (unit.type.equals("CLASS")) {
                    Row row = organizations.getRow(classIndex);
                    if (row == null) row = organizations.createRow(classIndex);
                    row.createCell(3).setCellValue(orgPath(unit)); classIndex++;
                }
            }
            var helper = sheet.getDataValidationHelper();
            var genders = helper.createValidation(helper.createExplicitListConstraint(new String[]{Presentation.label("MALE"), Presentation.label("FEMALE"), Presentation.label("OTHER")}), new org.apache.poi.ss.util.CellRangeAddressList(1, 5000, 3, 3));
            genders.setShowErrorBox(true); genders.createErrorBox("性别不正确", "请从下拉列表选择男、女、其他，或留空。"); sheet.addValidationData(genders);
            var roles = helper.createValidation(helper.createExplicitListConstraint(new String[]{Presentation.label("USER"), Presentation.label("TEACHER_ADMIN"), Presentation.label("STUDENT_ADMIN"), Presentation.label("MEDICAL_INSURANCE_ADMIN")}), new org.apache.poi.ss.util.CellRangeAddressList(1, 5000, 5, 5));
            roles.setShowErrorBox(true); roles.createErrorBox("身份不正确", "请从下拉列表中选择身份。"); sheet.addValidationData(roles);
            if (classIndex > 1) {
                var range = workbook.createName(); range.setNameName("AvailableClasses"); range.setRefersToFormula("'组织对照'!$D$2:$D$" + classIndex);
                var classes = helper.createValidation(helper.createFormulaListConstraint("AvailableClasses"), new org.apache.poi.ss.util.CellRangeAddressList(1, 5000, 4, 4));
                sheet.addValidationData(classes);
            }
            var textStyle = workbook.createCellStyle(); textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var headerStyle = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); font.setFontName("Microsoft YaHei"); headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (Sheet page : workbook) { page.createFreezePane(0, 1); for (int c = 0; c < page.getRow(0).getLastCellNum(); c++) { page.setDefaultColumnStyle(c, textStyle); page.setColumnWidth(c, (c == 0 || c == 4 || c == 6 ? 42 : 24) * 256); page.getRow(0).getCell(c).setCellStyle(headerStyle); } }
            instructions.setColumnWidth(1, 100 * 256);
            var wrapped = workbook.createCellStyle(); wrapped.setWrapText(true);
            for (int i = 1; i < notes.size(); i++) { instructions.getRow(i).getCell(1).setCellStyle(wrapped); instructions.getRow(i).setHeightInPoints(42); }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); workbook.write(out); return out.toByteArray();
        } catch (IOException e) { throw new ApiError(503, "AUDIT_UNAVAILABLE", "error.service.unavailable"); }
    }
    private void writeRow(Sheet sheet, int index, List<String> values) { Row row = sheet.getRow(index); if (row == null) row = sheet.createRow(index); for (int i = 0; i < values.size(); i++) row.createCell(i).setCellValue(values.get(i)); }
    private String orgPath(OrgUnit unit) { List<String> names = new ArrayList<>(); Set<UUID> visited = new HashSet<>(); for (OrgUnit current = unit; current != null && visited.add(current.id); current = orgUnits.get(current.parentId)) { names.addFirst(current.name); if (current.parentId == null) break; } return String.join(" / ", names); }

    public synchronized Map<String, Object> createImport(HttpServletRequest req, MultipartFile file, String key, String requestId) {
        Principal p = requireSystem(req, requestId);byte[] source;try{source=file==null?new byte[0]:file.getBytes();}catch(IOException failure){throw new ApiError(400,"VALIDATION_FAILED","error.import.unreadable");}String digest="import-upload:"+Objects.requireNonNullElse(file==null?null:file.getOriginalFilename(),"")+":"+sha256(source);String scopedKey=idempotencyKey(p.accountId(),"account-import-upload",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay; ImportJob job = new ImportJob(); importJobs.put(job.id, job);
        long size=file==null?0:file.getSize();append("ACCOUNT.IMPORT_VALIDATION.REQUESTED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "SUCCESS", requestId, "size:" + size, List.of());
        if (file == null || file.isEmpty() || file.getSize() > 10_000_000 || !Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            job.state = "INVALID"; job.errors.add(importError("",1,"file","INVALID_FILE"));
            append("ACCOUNT.IMPORT_VALIDATION.FAILED", p.accountId(), p.workspace(), p.channel(), "ALLOW", "FAILED", requestId, "invalid_file", List.of());
            Map<String,Object> response=linked("jobId",job.id,"state",job.state,"controlRequestId",null);cacheIdempotent(scopedKey,digest,response);return response;
        }
        job.state = "VALIDATING";
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(source))) {
            boolean chinese = wb.getSheet(Presentation.template("sheet")) != null;
            if (!chinese) for(String required:List.of("accounts","role_assignments","assignment_scopes"))if(wb.getSheet(required)==null)job.errors.add(importError(required,1,"sheet","MISSING_SHEET"));
            if(wb instanceof XSSFWorkbook xssf&&!xssf.getExternalLinksTable().isEmpty())job.errors.add(importError("",1,"file","EXTERNAL_LINKS_FORBIDDEN"));
            for(Sheet sheet:wb)for(Row row:sheet)for(Cell cell:row)if(cell.getCellType()==CellType.FORMULA)job.errors.add(importError(sheet.getSheetName(),row.getRowNum()+1,"file","FORMULA_FORBIDDEN"));
            for (int s = 0; s < wb.getNumberOfSheets(); s++) { Sheet page = wb.getSheetAt(s); if (wb.isSheetHidden(s) || wb.isSheetVeryHidden(s)) job.errors.add(importError(page.getSheetName(),1,"sheet","HIDDEN_DATA_FORBIDDEN")); for (Row row : page) { if (row.getZeroHeight()) job.errors.add(importError(page.getSheetName(),row.getRowNum()+1,"file","HIDDEN_DATA_FORBIDDEN")); for (Cell value : row) if (page.isColumnHidden(value.getColumnIndex())) job.errors.add(importError(page.getSheetName(),row.getRowNum()+1,"file","HIDDEN_DATA_FORBIDDEN")); } }
            if(job.errors.isEmpty()) { if (chinese) parseChineseWorkbook(wb,job); else { validateLegacyHeaders(wb,job); if(job.errors.isEmpty())parseImportWorkbook(wb,job); } }
            if (job.errors.isEmpty() && job.rows.isEmpty()) job.errors.add(importError(chinese ? Presentation.template("sheet") : "accounts", 2, "file", "EMPTY_FILE"));
            job.rowCount = job.rows.size(); job.organizationRevision = orgRevision.get(); job.state = job.errors.isEmpty() ? "VALID" : "INVALID";
            append(job.errors.isEmpty() ? "ACCOUNT.IMPORT_VALIDATION.SUCCEEDED" : "ACCOUNT.IMPORT_VALIDATION.FAILED", p.accountId(), p.workspace(), p.channel(), "ALLOW", job.errors.isEmpty() ? "SUCCESS" : "FAILED", requestId, null, List.of());
        } catch (Exception e) {
            job.state = "FAILED"; job.errors.add(importError("",1,"file","UNREADABLE"));
            append("ACCOUNT.IMPORT_VALIDATION.FAILED",p.accountId(),p.workspace(),p.channel(),"ALLOW","FAILED",requestId,"unreadable",List.of());
        }
        Map<String,Object> response=linked("jobId",job.id,"state",job.state,"controlRequestId",null);cacheIdempotent(scopedKey,digest,response);return response;
    }
    @ReadOnlyState
    public Map<String, Object> importView(ImportJob j) { return Map.of("id", j.id, "state", j.state, "templateVersion", j.templateVersion, "rowCount", j.rowCount, "errorCount", j.errors.size(), "organizationRevision", j.organizationRevision); }
    @ReadOnlyState
    public Map<String, Object> importStatus(HttpServletRequest req, UUID id, String requestId) { requireSystem(req, requestId); ImportJob j=importJobs.get(id); if(j==null) throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound"); return importView(j); }
    @ReadOnlyState
    public Map<String, Object> importErrors(HttpServletRequest req, UUID id, String requestId) { requireSystem(req, requestId); ImportJob j=importJobs.get(id); if(j==null) throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound"); return linked("items", j.errors, "nextCursor", null, "hasMore", false); }
    public synchronized Map<String, Object> commitImport(HttpServletRequest req, UUID id, long revision, int expected, String reason, String key, String requestId) {
        Principal p=requireSystem(req,requestId);String digest="import:"+id+":"+revision+":"+expected;String scopedKey=idempotencyKey(p.accountId(),"account-import-commit",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;
        ImportJob j=importJobs.get(id); if(j==null) throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");
        if(!j.state.equals("VALID")||j.organizationRevision!=revision||j.rowCount!=expected||orgRevision.get()!=revision) throw new ApiError(409,"IMPORT_REVALIDATION_REQUIRED","error.import.revalidation");
        if(j.rows.stream().anyMatch(row->loginIndex.containsKey(normalize(String.valueOf(row.get("login_name"))))||personIndex.containsKey(normalize(String.valueOf(row.get("person_no"))))))throw new ApiError(409,"DUPLICATE","error.account.duplicate");
        List<Account> pending=new ArrayList<>();Map<UUID,OneTimeCredential> credentials=new LinkedHashMap<>();
        for(Map<String,Object> row:j.rows){Account a=new Account();a.loginName=String.valueOf(row.get("login_name"));a.displayName=String.valueOf(row.get("display_name"));a.personNo=String.valueOf(row.get("person_no"));a.gender=normalizeGender(row.get("gender") instanceof String value?value:null);a.classId=(UUID)row.get("class_id");String temp=generateTemporaryPassword();a.passwordHash=passwordEncoder.encode(temp);for(Map<String,Object> assignment:castMaps(row.get("role_assignments")))addRole(a,String.valueOf(assignment.get("roleCode")),ids(assignment.get("scopeCeilings")));pending.add(a);credentials.put(a.id,new OneTimeCredential(temp));}
        for(Account a:pending){accounts.put(a.id,a);loginIndex.put(normalize(a.loginName),a.id);personIndex.put(normalize(a.personNo),a.id);}
        j.state="COMMITTED";j.committed=true;j.committedAt=Instant.now();j.credentialContent=credentialCsv(credentials);credentials.clear();append("ACCOUNT.IMPORT.COMMITTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,pending.stream().map(a->a.id).toList());Map<String,Object> response=linked("jobId",j.id,"state","COMMITTED","controlRequestId",null);cacheIdempotent(scopedKey,digest,response);return response;
    }

    public synchronized Map<String, Object> createExport(HttpServletRequest req, Map<String, Object> body, String key, String requestId) { Principal p=requireSystem(req,requestId);String digest="account-export:"+String.valueOf(body);String scopedKey=idempotencyKey(p.accountId(),"account-export-create",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;Map<String,Object> selection=asMap(body.get("selection")); String purpose=String.valueOf(body.get("purpose")); if(selection==null||purpose.length()<2) throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed"); ExportJob j=new ExportJob();j.purpose=purpose;j.mode=String.valueOf(selection.get("mode")); if(j.mode.equals("SELECTED")){j.targets=ids(selection.get("accountIds"));if(j.targets.isEmpty()||j.targets.size()!=new HashSet<>(j.targets).size()||j.targets.stream().anyMatch(id->!accounts.containsKey(id)))throw new ApiError(400,"VALIDATION_FAILED","error.export.selection");}else if(j.mode.equals("FILTERED_ALL")){Map<String,Object> filter=Objects.requireNonNullElse(asMap(selection.get("filter")),Map.of());j.targets=filterAccounts(filter).stream().map(a->a.id).sorted().toList();int expected=((Number)selection.getOrDefault("expectedCount",-1)).intValue();if(expected!=j.targets.size())throw new ApiError(409,"EXPORT_SELECTION_CHANGED","error.export.selectionChanged");}else throw new ApiError(400,"VALIDATION_FAILED","error.export.mode");j.resultCount=j.targets.size();exportJobs.put(j.id,j);append("ACCOUNT.EXPORT.REQUESTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,purpose,j.targets);j.content=exportCsv(j.targets,purpose,j.id);j.state="READY";j.completedAt=Instant.now();append("ACCOUNT.EXPORT.COMPLETED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,null,j.targets);Map<String,Object> response=linked("jobId",j.id,"state",j.state,"controlRequestId",null);cacheIdempotent(scopedKey,digest,response);return response; }
    @ReadOnlyState
    public Map<String,Object> exportStatus(HttpServletRequest req,UUID id,String requestId){requireSystem(req,requestId);ExportJob j=exportJobs.get(id);if(j==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");return exportStatusView(j);}
    public synchronized Map<String,Object> downloadTicket(HttpServletRequest req,UUID id,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="account-export-ticket:"+p.accountId()+":"+id;String scopedKey=idempotencyKey(p.accountId(),"account-export-ticket",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;ExportJob j=exportJobs.get(id);if(j==null||!j.state.equals("READY")||j.downloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");Map<String,Object> result=issueDownloadTicket(p,"ACCOUNT_EXPORT",id);cacheIdempotent(scopedKey,digest,result);return result;}

    @ReadOnlyState
    public Map<String,Object> auditSearch(HttpServletRequest req, Map<String,String> filters, String requestId){Principal p=requireSystem(req,requestId);Instant from=parseInstant(filters.get("occurredFrom"),null);Instant to=parseInstant(filters.get("occurredTo"),null);if(from==null||to==null||!to.isAfter(from)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0)throw new ApiError(400,"VALIDATION_FAILED","error.audit.range");UUID actor=parseUuid(filters.get("actorAccountId"));UUID subject=parseUuid(filters.get("subjectAccountId"));AuditCursor after=decodeAuditCursor(filters.get("cursor"));int limit=Math.min(200,Math.max(1,parseInt(filters.get("limit"),50)));List<AuditEvent> matching=auditEvents.values().stream().filter(e->!e.occurredAt.isBefore(from)&&e.occurredAt.isBefore(to)).filter(e->filters.get("eventCode")==null||e.code.equals(filters.get("eventCode"))).filter(e->filters.get("requestId")==null||e.requestId.equals(filters.get("requestId"))).filter(e->actor==null||Objects.equals(e.actor,actor)).filter(e->subject==null||e.subjects.contains(subject)).sorted(Comparator.comparing((AuditEvent e)->e.occurredAt).reversed().thenComparing(e->e.id)).filter(e->after==null||e.occurredAt.isBefore(after.occurredAt())||e.occurredAt.equals(after.occurredAt())&&e.id.compareTo(after.id())>0).toList();List<AuditEvent> page=matching.stream().limit(limit).toList();boolean hasMore=matching.size()>page.size();append("AUDIT.SEARCHED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"filters_applied",page.stream().flatMap(e->e.subjects.stream()).distinct().toList());return linked("items",page.stream().map(this::auditSummary).toList(),"nextCursor",hasMore?encodeAuditCursor(page.getLast()):null,"hasMore",hasMore);}
    @ReadOnlyState
    public Map<String,Object> auditDetail(HttpServletRequest req,UUID id,String requestId){Principal p=requireSystem(req,requestId);AuditEvent e=auditEvents.get(id);if(e==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");append("AUDIT.DETAIL_VIEWED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,null,e.subjects);return auditDetail(e);}
    public synchronized Map<String,Object> auditExport(HttpServletRequest req,Map<String,Object> body,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="audit-export:"+canonical(body);String scopedKey=idempotencyKey(p.accountId(),"audit-export-create",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;String reason=String.valueOf(body.get("purpose"));Instant from=parseInstant(String.valueOf(body.get("occurredFrom")),null),to=parseInstant(String.valueOf(body.get("occurredTo")),null);Set<String> requestedCodes=stringSet(body.get("eventCodes"));if(reason.length()<2||from==null||to==null||!to.isAfter(from)||Duration.between(from,to).compareTo(Duration.ofDays(31))>0||!EVENTS.containsAll(requestedCodes))throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");ExportJob job=new ExportJob();job.audit=true;job.purpose=reason;job.filter=Map.copyOf(body);job.state="PENDING";auditExportJobs.put(job.id,job);ControlRequest c=new ControlRequest("AUDIT_EXPORT",p.accountId(),List.of(p.accountId()),reason);c.jobId=job.id;c.commandHash=sha256(digest);c.organizationRevision=orgRevision.get();c.authorizationRevision=authzRevision.get();c.targetVersions=targetVersions(c.targets);controls.put(c.id,c);append("AUDIT.EXPORT.REQUESTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of());append("CONTROL.REQUESTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(p.accountId()));Map<String,Object> response=linked("jobId",job.id,"state","PENDING_APPROVAL","controlRequestId",c.id);cacheIdempotent(scopedKey,digest,response);return response;}
    @ReadOnlyState
    public Map<String,Object> auditExportStatus(HttpServletRequest req,UUID id,String requestId){requireSystem(req,requestId);ExportJob j=auditExportJobs.get(id);if(j==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");return exportStatusView(j);}
    public synchronized Map<String,Object> auditDownloadTicket(HttpServletRequest req,UUID id,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="audit-export-ticket:"+p.accountId()+":"+id;String scopedKey=idempotencyKey(p.accountId(),"audit-export-ticket",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;ExportJob j=auditExportJobs.get(id);if(j==null||!j.state.equals("READY")||j.downloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");Map<String,Object> result=issueDownloadTicket(p,"AUDIT_EXPORT",id);cacheIdempotent(scopedKey,digest,result);return result;}

    public synchronized Map<String,Object> refresh(HttpServletRequest req, String suppliedRefreshToken, String channel, String key, String requestId) {
        String raw = suppliedRefreshToken == null || suppliedRefreshToken.isBlank() ? cookie(req, "SHTONG_REFRESH") : suppliedRefreshToken;
        if (raw == null || raw.isBlank()) throw new ApiError(401, "AUTHENTICATION_REQUIRED", "error.authentication.required");
        String tokenHash=hashToken(raw);String digest = "refresh:" + tokenHash;String scopedKey=idempotencyKey(null,"refresh-"+tokenHash,key);
        Map<String,Object> replay = replay(scopedKey, digest);
        if (replay != null) return replay;
        RefreshGrant grant = refreshTokens.get(hashToken(raw));
        if (grant == null || grant.revoked || grant.expiresAt.isBefore(Instant.now())) {
            append("AUTH.TOKEN.REFRESH_FAILED", grant == null ? null : grant.accountId, null, channel, "DENY", "DENIED", requestId, "invalid_refresh", List.of());
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        if (grant.consumed) {
            revokeFamily(grant.familyId);
            append("AUTH.TOKEN.REFRESH_FAILED", grant.accountId, null, channel, "DENY", "DENIED", requestId, "refresh_replay", List.of(grant.accountId));
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        Session old = sessions.get(grant.sessionToken);
        Account account = accounts.get(grant.accountId);
        if (old == null || account == null || old.revoked || !account.lifecycle.equals("ACTIVE") || old.authzVersion != account.authzVersion) {
            grant.revoked = true;
            append("AUTH.TOKEN.REFRESH_FAILED", grant.accountId, null, channel, "DENY", "DENIED", requestId, "session_invalid", List.of(grant.accountId));
            throw new ApiError(401, "SESSION_REVOKED", "error.session.revoked");
        }
        grant.consumed = true; revokeSession(old);
        String token = issueToken(account, channel, old.restriction.equals("PASSWORD_CHANGE_ONLY")?old.restriction:profileRestriction(account), old.workspace, grant.familyId);
        Session replacement=sessions.get(token);Map<String,Object> response = sessionView(replacement, token);response.put("_transportToken",replacement.token);response.put("_transportRefreshToken",replacement.refreshToken);
        cacheIdempotent(scopedKey, digest, response);
        append("AUTH.TOKEN.REFRESHED", account.id, old.workspace, channel, "ALLOW", "SUCCESS", requestId, null, List.of(account.id));
        return response;
    }

    public synchronized Map<String,Object> credentialTicket(HttpServletRequest req, UUID commandId, String key, String requestId) {
        Principal p = requireSystem(req, requestId);String digest="reset-credential-ticket:"+p.accountId()+":"+commandId;String scopedKey=idempotencyKey(p.accountId(),"reset-credential-ticket",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay; Command c = commands.get(commandId); if (c == null || !c.type.equals("RESET") || !c.state.equals("COMPLETED") || c.credentialContent==null || c.credentialDownloaded) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");
        Map<String,Object> result=issueDownloadTicket(p,"RESET_CREDENTIAL",commandId);cacheIdempotent(scopedKey,digest,result);return result;
    }
    public synchronized Map<String,Object> importTicket(HttpServletRequest req, UUID jobId, String key, String requestId) {
        Principal p = requireSystem(req, requestId);String digest="import-credential-ticket:"+p.accountId()+":"+jobId;String scopedKey=idempotencyKey(p.accountId(),"import-credential-ticket",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay; ImportJob j = importJobs.get(jobId); if (j == null || !j.committed || j.credentialContent==null || j.credentialDownloaded) throw new ApiError(404, "RESOURCE_NOT_FOUND", "error.resource.notFound");
        Map<String,Object> result=issueDownloadTicket(p,"IMPORT_CREDENTIAL",jobId);cacheIdempotent(scopedKey,digest,result);return result;
    }
    public synchronized DownloadPayload consumeDownload(HttpServletRequest req,UUID ticketId,String secret,String requestId){Principal p=requireSystem(req,requestId);DownloadTicket ticket=downloadTickets.get(ticketId);if(ticket==null||ticket.consumed||ticket.expiresAt.isBefore(Instant.now())||!ticket.issuedTo.equals(p.accountId())||!constantTimeEquals(ticket.secretHash,hashToken(secret)))throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");DownloadPayload payload=switch(ticket.artifactType){case "ACCOUNT_EXPORT"->{ExportJob j=exportJobs.get(ticket.artifactId);if(j==null||j.content==null||j.downloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");byte[] content=j.content;j.content=null;j.downloaded=true;append("ACCOUNT.EXPORT.DOWNLOADED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,null,j.targets);yield new DownloadPayload(content,"text/csv; charset=utf-8","shtong-accounts-"+j.id+".csv");}case "AUDIT_EXPORT"->{ExportJob j=auditExportJobs.get(ticket.artifactId);if(j==null||j.content==null||j.downloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");byte[] content=j.content;j.content=null;j.downloaded=true;append("AUDIT.EXPORT.DOWNLOADED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,null,List.of());yield new DownloadPayload(content,"text/csv; charset=utf-8","shtong-audit-"+j.id+".csv");}case "RESET_CREDENTIAL"->{Command c=commands.get(ticket.artifactId);if(c==null||c.credentialContent==null||c.credentialDownloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");byte[] content=c.credentialContent;c.credentialContent=null;c.credentialDownloaded=true;append("AUTH.PASSWORD.RESET",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"credential_result_downloaded",c.targets);yield new DownloadPayload(content,"text/csv; charset=utf-8","shtong-reset-credentials-"+c.id+".csv");}case "IMPORT_CREDENTIAL"->{ImportJob j=importJobs.get(ticket.artifactId);if(j==null||j.credentialContent==null||j.credentialDownloaded)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");byte[] content=j.credentialContent;j.credentialContent=null;j.credentialDownloaded=true;append("ACCOUNT.IMPORT_RESULT.DOWNLOADED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"import_credential",j.rows.stream().map(row->loginIndex.get(normalize(String.valueOf(row.get("login_name"))))).filter(Objects::nonNull).toList());yield new DownloadPayload(content,"text/csv; charset=utf-8","shtong-import-credentials-"+j.id+".csv");}default->throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");};ticket.consumed=true;return payload;
    }

    @ReadOnlyState
    public List<Map<String,Object>> orgList(HttpServletRequest req,UUID parentId,Long revision,String requestId){Principal p=requireSystem(req,requestId);if(revision!=null&&revision!=orgRevision.get())throw new ApiError(409,"VERSION_CONFLICT","error.organization.revision");List<OrgUnit> list=orgUnits.values().stream().filter(o->o.status.equals("ACTIVE")).filter(o->parentId==null||Objects.equals(o.parentId,parentId)).sorted(Comparator.comparing(o->o.code)).toList();append("ORGANIZATION.SEARCHED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,null,list.stream().map(o->o.id).toList());return list.stream().map(this::orgView).toList();}
    public synchronized Map<String,Object> orgCreate(HttpServletRequest req,String type,UUID parentId,String code,String name,long expectedVersion,String reason,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="org-create:"+type+":"+parentId+":"+normalize(code)+":"+name+":"+expectedVersion+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"organization-create",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;validateOrg(type,parentId,code);if(expectedVersion!=0)throw new ApiError(409,"VERSION_CONFLICT","error.version.conflict");if(orgUnits.values().stream().anyMatch(o->o.type.equals(type)&&o.code.equalsIgnoreCase(code)&&o.status.equals("ACTIVE")))throw new ApiError(409,"DUPLICATE","error.organization.duplicate");OrgUnit o=new OrgUnit();o.type=type;o.parentId=parentId;o.code=code;o.name=name;orgUnits.put(o.id,o);orgRevision.incrementAndGet();append("ORGANIZATION.CREATED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(o.id));Map<String,Object> response=orgView(o);cacheIdempotent(scopedKey,digest,response);return response;}
    public synchronized Map<String,Object> orgUpdate(HttpServletRequest req,UUID id,String type,UUID parentId,String code,String name,long expectedVersion,String reason,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="org-update:"+id+":"+type+":"+parentId+":"+normalize(code)+":"+name+":"+expectedVersion+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"organization-update",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;OrgUnit o=orgUnits.get(id);if(o==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");if(o.version!=expectedVersion)throw new ApiError(409,"VERSION_CONFLICT","error.version.conflict");validateOrg(type,parentId,code);if(Objects.equals(id,parentId)||isDescendant(id,parentId))throw new ApiError(422,"VALIDATION_FAILED","error.organization.cycle");if(orgUnits.values().stream().anyMatch(other->!other.id.equals(id)&&other.type.equals(type)&&other.code.equalsIgnoreCase(code)&&other.status.equals("ACTIVE")))throw new ApiError(409,"DUPLICATE","error.organization.duplicate");o.type=type;o.parentId=parentId;o.code=code;o.name=name;o.version++;orgRevision.incrementAndGet();append("ORGANIZATION.UPDATED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(o.id));Map<String,Object> response=orgView(o);cacheIdempotent(scopedKey,digest,response);return response;}
    public synchronized void orgDelete(HttpServletRequest req,UUID id,String reason,String key,String requestId){Principal p=requireSystem(req,requestId);String digest="org-delete:"+id+":"+reason;String scopedKey=idempotencyKey(p.accountId(),"organization-delete",key);Boolean replay=replay(scopedKey,digest);if(Boolean.TRUE.equals(replay))return;OrgUnit o=orgUnits.get(id);if(o==null||!o.status.equals("ACTIVE"))throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");boolean usedByAccount=accounts.values().stream().filter(a->a.lifecycle.equals("ACTIVE")).anyMatch(a->Objects.equals(a.classId,id)||a.roles.values().stream().filter(this::isRoleActive).anyMatch(r->r.ceilings.contains(id)));boolean hasActiveChildren=orgUnits.values().stream().anyMatch(child->Objects.equals(child.parentId,id)&&child.status.equals("ACTIVE"));if(usedByAccount||hasActiveChildren)throw new ApiError(409,"ORGANIZATION_IN_USE","error.organization.inUse");o.status="DELETED";o.version++;orgRevision.incrementAndGet();append("ORGANIZATION.DELETED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(id));cacheIdempotent(scopedKey,digest,Boolean.TRUE);}

    @ReadOnlyState
    public Map<String,Object> controlList(HttpServletRequest req,String state,String cursor,int limit,String requestId){Principal p=requireSystem(req,requestId);ControlCursor after=decodeControlCursor(cursor);List<ControlRequest> matching=controls.values().stream().filter(c->state==null||c.state.equals(state)).sorted(Comparator.comparing((ControlRequest c)->c.requestedAt).reversed().thenComparing(c->c.id)).filter(c->after==null||c.requestedAt.isBefore(after.requestedAt())||c.requestedAt.equals(after.requestedAt())&&c.id.compareTo(after.id())>0).toList();List<ControlRequest> page=matching.stream().limit(Math.min(200,Math.max(1,limit))).toList();boolean hasMore=matching.size()>page.size();append("AUDIT.SEARCHED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"control_requests",page.stream().flatMap(c->c.targets.stream()).distinct().toList());return linked("items",page.stream().map(this::controlView).toList(),"nextCursor",hasMore?encodeControlCursor(page.getLast()):null,"hasMore",hasMore);}
    @ReadOnlyState
    public Map<String,Object> controlDetail(HttpServletRequest req,UUID id,String requestId){Principal p=requireSystem(req,requestId);ControlRequest c=controls.get(id);if(c==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");append("AUDIT.DETAIL_VIEWED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"control_request",c.targets);return controlView(c);}
    public synchronized Map<String,Object> controlDecision(HttpServletRequest req,UUID id,long expected,String reason,boolean approve,String key,String requestId){
        Principal p=requireSystem(req,requestId);String digest="control:"+id+":"+expected+":"+approve+":"+reason;String scopedKey=idempotencyKey(p.accountId(),approve?"control-approve":"control-reject",key);Map<String,Object> replay=replay(scopedKey,digest);if(replay!=null)return replay;ControlRequest c=controls.get(id);if(c==null)throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");
        if(c.requestedBy.equals(p.accountId())||c.targets.contains(p.accountId()))throw new ApiError(403,"SEPARATION_OF_DUTIES_VIOLATION","error.control.separation");
        if(c.version!=expected||!c.state.equals("PENDING")||c.commandHash==null)throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");
        if(c.expiresAt.isBefore(Instant.now())){c.state="EXPIRED";c.decidedAt=Instant.now();c.version++;append("CONTROL.EXPIRED",p.accountId(),p.workspace(),p.channel(),"ALLOW","FAILED",requestId,"expired",c.targets);Map<String,Object> response=controlView(c);cacheIdempotent(scopedKey,digest,response);return response;}
        if(!approve){c.state="REJECTED";c.decidedBy=p.accountId();c.decidedAt=Instant.now();c.version++;append("CONTROL.REJECTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,c.targets);Map<String,Object> response=controlView(c);cacheIdempotent(scopedKey,digest,response);return response;}
        Account requester=accounts.get(c.requestedBy);if(requester==null||!requester.lifecycle.equals("ACTIVE")||!activeRole(requester,"SYSTEM_ADMIN")||c.targetVersions==null||c.targetVersions.entrySet().stream().anyMatch(entry->{Account target=accounts.get(entry.getKey());return target==null||target.version!=entry.getValue();})||!controlHashMatches(c))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");
        if(c.commandType.equals("ASSIGN_SYSTEM_ADMIN")){Account target=accounts.get(c.targets.getFirst());if(target==null||!target.lifecycle.equals("ACTIVE")||activeRole(target,"SYSTEM_ADMIN"))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");RoleAssignment created=addRole(target,c.roleCode,c.ceilingIds);created.validFrom=c.validFrom;created.validTo=c.validTo;target.authzVersion++;target.version++;authzRevision.incrementAndGet();revokeAll(target.id,"authorization_changed");append("AUTHZ.ROLE.ASSIGNED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,c.reason,List.of(target.id));}
        else if(c.commandType.equals("REVOKE_SYSTEM_ADMIN")){if(accounts.values().stream().filter(a->a.lifecycle.equals("ACTIVE")&&activeRole(a,"SYSTEM_ADMIN")).count()<=1)throw new ApiError(409,"LAST_SYSTEM_ADMIN_PROTECTED","error.lastSystemAdmin");Account target=accounts.get(c.targets.getFirst());if(target==null||!target.lifecycle.equals("ACTIVE"))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");RoleAssignment r=target.roles.values().stream().filter(x->x.id.equals(c.assignmentId)&&isRoleActive(x)).findFirst().orElseThrow(()->new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale"));r.status="REVOKED";r.version++;target.authzVersion++;target.version++;authzRevision.incrementAndGet();revokeAll(target.id,"role_revoked");append("AUTHZ.ROLE.REVOKED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,c.reason,List.of(target.id));}
        else if(c.commandType.equals("DELETE_ACCOUNTS")||c.commandType.equals("RESET_SYSTEM_ADMIN")){Command command=commands.get(c.jobId);if(command==null||!command.state.equals("PENDING")||!command.targets.equals(c.targets))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");executeCommand(command,p,requestId);}
        else if(c.commandType.equals("AUDIT_EXPORT")){ExportJob job=auditExportJobs.get(c.jobId);if(job==null||!job.state.equals("PENDING"))throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");List<AuditEvent> events=filterAuditForExport(job.filter);job.content=auditCsv(events,job.purpose,job.id);job.resultCount=events.size();job.state="READY";job.completedAt=Instant.now();}
        else throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");
        c.state="EXECUTED";c.decidedBy=p.accountId();c.decidedAt=Instant.now();c.version++;append("CONTROL.APPROVED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,c.targets);append("CONTROL.EXECUTED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,reason,c.targets);Map<String,Object> response=controlView(c);cacheIdempotent(scopedKey,digest,response);return response;
    }

    private Principal requireSystem(HttpServletRequest req,String requestId){Principal p=principal(req,"SYSTEM_ADMIN",false,requestId);Account a=accounts.get(p.accountId());if(!activeRole(a,"SYSTEM_ADMIN")){append("SESSION.CONTEXT_SWITCH_FAILED",p.accountId(),p.workspace(),p.channel(),"DENY","DENIED",requestId,"system_role_missing",List.of(p.accountId()));throw new ApiError(403,"ACCESS_DENIED","error.access.denied");}return p;}
    private String issueToken(Account a,String channel,String restriction,String workspace){return issueToken(a,channel,restriction,workspace,UUID.randomUUID());}
    private String issueToken(Account a,String channel,String restriction,String workspace,UUID familyId){
        String token=randomToken(); Instant exp=SESSION_END;
        Session session=new Session(a.id,channel,token,exp,restriction,workspace,a.authzVersion,familyId);
        session.refreshToken=randomToken();session.refreshExpiresAt=SESSION_END;
        sessions.put(token,session);refreshTokens.put(hashToken(session.refreshToken),new RefreshGrant(familyId,a.id,token,session.refreshExpiresAt));return token;
    }
    private Map<String,Object> sessionView(Session s,String token){Map<String,Object> m=new LinkedHashMap<>();m.put("sessionId",s.id);m.put("context",Map.of("id",s.id,"workspace",s.workspace,"assuranceLevel","AAL1","expiresAt",s.expiresAt));m.put("tokenDelivery",s.channel.startsWith("WEB")?"HTTP_ONLY_COOKIE":"RESPONSE_BODY");m.put("mobileTokens",s.channel.startsWith("WEB")?null:mobileTokens(s));return m;}
    private Map<String,Object> mobileTokens(Session s){return Map.of("tokenType","Bearer","accessToken",s.token,"refreshToken",s.refreshToken,"accessTokenExpiresAt",s.expiresAt,"refreshTokenExpiresAt",s.refreshExpiresAt);}
    private String bearerOrCookie(HttpServletRequest req){String h=req.getHeader("Authorization");if(h!=null&&h.startsWith("Bearer "))return h.substring(7);if(req.getCookies()!=null)for(var c:req.getCookies())if(c.getName().equals("SHTONG_SESSION"))return c.getValue();return null;}
    private void revokeAll(UUID id,String reason){sessions.values().stream().filter(s->s.accountId.equals(id)).forEach(s->{revokeSession(s);revokeFamily(s.tokenFamilyId);});}
    private synchronized void append(String code,UUID actor,String workspace,String channel,String decision,String outcome,String requestId,String reason,List<UUID> subjects){
        appendAudit(code,actor,workspace,channel,decision,outcome,requestId,reason,subjects,reason==null?List.of():List.of(Map.of("field","reasonCode","classification","INTERNAL","after",reason)));
    }
    private synchronized void appendWithChanges(String code,Principal principal,Account subject,String requestId,String reason,List<Map<String,Object>> changes){
        List<Map<String,Object>> detailed=new ArrayList<>(changes);detailed.add(linked("field","reasonCode","classification","INTERNAL","before",null,"after",reason));
        appendAudit(code,principal.accountId(),principal.workspace(),principal.channel(),"ALLOW","SUCCESS",requestId,reason,List.of(subject.id),detailed);
    }
    private synchronized void appendAudit(String code,UUID actor,String workspace,String channel,String decision,String outcome,String requestId,String reason,List<UUID> subjects,List<Map<String,Object>> changes){
        if(!EVENTS.contains(code))throw new IllegalArgumentException("Unknown frozen audit event: "+code);
        List<UUID> safeSubjects=subjects==null?List.of():List.copyOf(subjects);List<Map<String,Object>> resources=safeSubjects.stream().map(id->Map.<String,Object>of("resourceType","ACCOUNT_OR_RESOURCE","resourceId",id.toString())).toList();
        List<Map<String,Object>> evidence=List.of(linked("decision",decision,"workspace",Objects.requireNonNullElse(workspace,"ANONYMOUS"),"authorizationVersion",authzRevision.get(),"organizationRevision",orgRevision.get()));
        String rid=requestId==null?"req_"+UUID.randomUUID():requestId;String previous=HexFormat.of().formatHex(previousAuditHash);
        // PostgreSQL keeps microseconds; truncating here lets the persisted row reproduce the same hash.
        UUID id=UUID.randomUUID();Instant occurredAt=Instant.now().truncatedTo(ChronoUnit.MICROS);List<Map<String,Object>> safeChanges=changes==null?List.of():changes;
        String current=auditHash(AUDIT_HASH_FULL,id,code,occurredAt,actor,workspace,channel,decision,outcome,rid,reason,safeSubjects,resources,safeChanges,evidence,previous);
        register(new AuditEvent(id,occurredAt,AUDIT_HASH_FULL,code,actor,workspace,channel,decision,outcome,rid,reason,safeSubjects,resources,safeChanges,evidence,current,previous));
    }
    private boolean hasStudentRecord(Account a) { return activeRole(a,"USER") || "GRADUATED".equals(a.lifecycle); }
    private Map<String,Object> summary(Account a){return linked("id",a.id,"loginName",a.loginName,"displayName",a.displayName,"personNo",a.personNo,"gender",hasStudentRecord(a)?a.gender:null,"ethnicity",hasStudentRecord(a)?a.ethnicity:null,"age",hasStudentRecord(a)?ageFromId(a.idCardNumber):null,"lifecycleStatus",a.lifecycle,"securityStatus",a.security,"roleCodes",a.roles.values().stream().filter(this::isRoleActive).map(r->r.roleCode).distinct().toList(),"passwordChangeRequired",a.credentialState.equals("TEMPORARY"),"version",a.version,"graduatedAt",a.graduatedAt,"graduatedReason",a.graduatedReason);}
    private Map<String,Object> roleView(RoleAssignment r){String state=r.status.equals("ACTIVE")&&r.validTo!=null&&!r.validTo.isAfter(Instant.now())?"EXPIRED":r.status;return linked("id",r.id,"roleCode",r.roleCode,"status",state,"scopeCeilings",r.ceilings.stream().map(orgUnits::get).filter(Objects::nonNull).map(this::orgView).toList(),"validFrom",r.validFrom,"validTo",r.validTo,"version",r.version);}
    private Map<String,Object> orgView(OrgUnit o){return linked("id",o.id,"type",o.type,"parentId",o.parentId,"code",o.code,"name",o.name,"status",o.status,"version",o.version);}
    private Map<String,Object> auditSummary(AuditEvent e){return linked("eventId",e.id,"eventCode",e.code,"occurredAt",e.occurredAt,"actorAccountId",e.actor,"authorizationDecision",e.decision,"outcome",e.outcome,"requestId",e.requestId,"subjectCount",e.subjects.size());}
    private Map<String,Object> auditDetail(AuditEvent e){return linked("eventId",e.id,"eventCode",e.code,"occurredAt",e.occurredAt,"actorAccountId",e.actor,"authorizationDecision",e.decision,"outcome",e.outcome,"requestId",e.requestId,"subjectCount",e.subjects.size(),"workspace",e.workspace,"channel",e.channel,"reasonCode",e.reason,"subjects",e.subjects,"resources",e.resources,"changes",e.changes,"authorizationEvidence",e.evidence,"payloadHash",e.payloadHash,"previousPayloadHash",e.previousPayloadHash);}
    private Map<String,Object> controlView(ControlRequest c){return linked("id",c.id,"commandType",c.commandType,"state",c.state,"requestedBy",c.requestedBy,"requestedAt",c.requestedAt,"expiresAt",c.expiresAt,"decidedBy",c.decidedBy,"decidedAt",c.decidedAt,"targetCount",c.targets.size(),"reason",c.reason,"version",c.version);}
    private RoleAssignment addRole(Account a,String role,List<UUID> ceilings){validateRoleAssignment(a,role,ceilings);RoleAssignment r=new RoleAssignment();r.roleCode=role;r.ceilings.addAll(ceilings);a.roles.put(r.id.toString(),r);return r;}
    private void validateRoleAssignment(Account a,String role,List<UUID> ceilings){if(ceilings==null||!ROLES.contains(role)||role.equals("SYSTEM_ADMIN")&&!ceilings.isEmpty()||activeRole(a,role)||ceilings.size()!=new HashSet<>(ceilings).size())throw new ApiError(422,"ROLE_SCOPE_TYPE_INVALID","error.role.invalid");if(role.equals("USER")&&!ceilings.isEmpty())throw new ApiError(422,"ROLE_SCOPE_TYPE_INVALID","error.role.invalid");if(role.equals("TEACHER_ADMIN")&&(ceilings.isEmpty()||ceilings.stream().map(orgUnits::get).anyMatch(o->o==null||!o.status.equals("ACTIVE")||!o.type.equals("GRADE"))))throw new ApiError(422,"ROLE_SCOPE_TYPE_INVALID","error.role.teacherScope");if((role.equals("STUDENT_ADMIN")||role.equals("MEDICAL_INSURANCE_ADMIN"))&&(ceilings.isEmpty()||ceilings.stream().map(orgUnits::get).anyMatch(o->o==null||!o.status.equals("ACTIVE")||!Set.of("COLLEGE","GRADE","CLASS").contains(o.type))))throw new ApiError(422,"ROLE_SCOPE_TYPE_INVALID",role.equals("MEDICAL_INSURANCE_ADMIN")?"error.role.medicalInsuranceScope":"error.role.studentScope");}
    private void validateOrg(String type,UUID parent,String code){if(!ORG_TYPES.contains(type)||code==null||code.isBlank())throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");if(type.equals("SCHOOL")&&parent!=null||!type.equals("SCHOOL")&&parent==null)throw new ApiError(422,"VALIDATION_FAILED","error.organization.parent");if(parent!=null){OrgUnit p=orgUnits.get(parent);if(p==null||!p.status.equals("ACTIVE")||!((p.type.equals("SCHOOL")&&type.equals("COLLEGE"))||(p.type.equals("COLLEGE")&&type.equals("GRADE"))||(p.type.equals("GRADE")&&type.equals("CLASS"))))throw new ApiError(422,"VALIDATION_FAILED","error.organization.parent");}}
    private List<Account> filterAccounts(Map<String,Object> f){String q=f.get("q") instanceof String s?s:null;Set<String> lifecycles=stringSet(f.get("lifecycleStatus"));Set<String> roles=stringSet(f.get("roleCodes"));Set<UUID> orgs=uuidSet(f.get("orgUnitIds"));return accounts.values().stream().filter(a->lifecycles.isEmpty()?a.lifecycle.equals("ACTIVE"):lifecycles.contains(a.lifecycle)).filter(a->q==null||(a.loginName+a.displayName+a.personNo).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).filter(a->roles.isEmpty()||roles.stream().anyMatch(role->activeRole(a,role))).filter(a->orgs.isEmpty()||orgs.stream().anyMatch(org->accountWithinOrg(a,org))).sorted(Comparator.comparing((Account a)->a.createdAt).thenComparing(a->a.id)).toList();}
    private byte[] exportCsv(List<UUID> ids,String purpose,UUID jobId){StringBuilder b=new StringBuilder();b.append("# 导出编号=").append(jobId).append("; 用途=").append(csv(purpose)).append("; 生成时间=").append(Instant.now()).append("\r\n");b.append("账户编号,登录账号,姓名,学工号,性别,账户状态,安全状态,身份,所属班级\r\n");for(UUID id:ids){Account a=accounts.get(id);if(a!=null)b.append(csv(a.id)).append(',').append(csv(a.loginName)).append(',').append(csv(a.displayName)).append(',').append(csv(a.personNo)).append(',').append(csv(activeRole(a,"USER")&&a.gender!=null?Presentation.label(a.gender):"")).append(',').append(csv(Presentation.label(a.lifecycle))).append(',').append(csv(Presentation.label(a.security))).append(',').append(csv(a.roles.values().stream().filter(this::isRoleActive).map(r->Presentation.label(r.roleCode)).distinct().collect(Collectors.joining("；")))).append(',').append(csv(a.classId==null||!orgUnits.containsKey(a.classId)?"":orgPath(orgUnits.get(a.classId)))).append("\r\n");}return withUtf8Bom(b.toString());}
    /** 20 characters from an unambiguous alphabet; always meets the password policy (upper, lower and digit). */
    private String generateTemporaryPassword(){
        while(true){
            StringBuilder value=new StringBuilder(20);
            for(int i=0;i<20;i++)value.append(TEMPORARY_PASSWORD_ALPHABET.charAt(secureRandom.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
            String candidate=value.toString();
            if(meetsPasswordPolicy(candidate))return candidate;
        }
    }
    private String normalize(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}
    private int parseInt(String s,int d){try{return Integer.parseInt(s);}catch(Exception e){return d;}}
    private void validateTargetIds(List<UUID> ids,int expected){if(ids==null||ids.isEmpty()||ids.size()>5000||ids.size()!=expected||ids.stream().distinct().count()!=ids.size())throw new ApiError(400,"VALIDATION_FAILED","error.collection.invalid");}
    private Map<UUID,Long> targetVersions(Collection<UUID> ids){Map<UUID,Long> versions=new LinkedHashMap<>();for(UUID id:ids){Account account=accounts.get(id);if(account!=null)versions.put(id,account.version);}return Map.copyOf(versions);}
    private boolean controlHashMatches(ControlRequest control){
        if(control.commandHash==null)return false;
        String material;
        if(control.commandType.equals("ASSIGN_SYSTEM_ADMIN"))material="assign-system:"+control.targets.getFirst()+":"+control.ceilingIds+":"+control.validFrom+":"+control.validTo+":"+control.reason;
        else if(control.commandType.equals("REVOKE_SYSTEM_ADMIN"))material="revoke-system:"+control.assignmentId+":"+control.reason;
        else if(control.commandType.equals("DELETE_ACCOUNTS")||control.commandType.equals("RESET_SYSTEM_ADMIN")){Command command=commands.get(control.jobId);if(command==null)return false;material=command.type+":"+command.targets.stream().sorted().toList()+":"+command.reason;}
        else if(control.commandType.equals("AUDIT_EXPORT")){ExportJob job=auditExportJobs.get(control.jobId);if(job==null)return false;material="audit-export:"+canonical(job.filter);}
        else return false;
        return constantTimeEquals(control.commandHash,sha256(material));
    }
    private String canonical(Object value){if(value instanceof Map<?,?> map)return map.entrySet().stream().sorted(Comparator.comparing(entry->String.valueOf(entry.getKey()))).map(entry->String.valueOf(entry.getKey())+"="+canonical(entry.getValue())).collect(Collectors.joining(",","{","}"));if(value instanceof Collection<?> collection)return collection.stream().map(this::canonical).collect(Collectors.joining(",","[","]"));return String.valueOf(value);}
    private List<UUID> ids(Object value){if(!(value instanceof Collection<?> c))return List.of();return c.stream().map(x->{try{Object raw=x instanceof Map<?,?> map?map.get("orgUnitId"):x;return UUID.fromString(String.valueOf(raw));}catch(Exception e){return null;}}).filter(Objects::nonNull).toList();}
    private Set<String> stringSet(Object value){if(!(value instanceof Collection<?> c))return Set.of();return c.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));}
    private Set<UUID> uuidSet(Object value){return new LinkedHashSet<>(ids(value));}
    private String cell(Row row,int index){Cell c=row.getCell(index);return c==null?"":c.getCellType()==CellType.STRING?c.getStringCellValue().trim():new DataFormatter().formatCellValue(c).trim();}
    private Map<String,Object> importError(int row,String field,String code){return importError("accounts",row,field,code);}
    private Map<String,Object> importError(String sheet,int row,String field,String code){return Map.of("sheet",sheet,"row",row,"field",field,"code",code,"messageKey","error.import."+code.toLowerCase(Locale.ROOT));}
    private Map<String,Object> linked(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}

    private String idempotencyKey(UUID actor,String operation,String key){if(key==null||key.length()<16||key.length()>128)throw new ApiError(400,"VALIDATION_FAILED","error.idempotency.required");return Objects.toString(actor,"ANONYMOUS")+":"+operation+":"+key;}
    @SuppressWarnings("unchecked") private <T> T replay(String key,String digest){IdempotentResult old=idempotency.get(key);if(old==null)return null;if(!old.digest().equals(digest))throw new ApiError(409,"VERSION_CONFLICT","error.idempotency.conflict");return (T)old.response();}
    private void cacheIdempotent(String key,String digest,Object response){IdempotentResult old=idempotency.putIfAbsent(key,new IdempotentResult(digest,response,Instant.now()));if(old!=null&&!old.digest().equals(digest))throw new ApiError(409,"VERSION_CONFLICT","error.idempotency.conflict");}
    private LoginResult deniedLogin(){return new LoginResult("DENIED",null,null,List.of(),null,null,null);}
    private boolean rateLimited(String key){AttemptWindow w=loginRate.get(key);return w!=null&&Duration.between(w.startedAt,Instant.now()).toMinutes()<1&&w.count>=10;}
    private void registerLoginFailure(Account account,String... keys){for(String key:keys){AttemptWindow w=loginRate.computeIfAbsent(key,k->new AttemptWindow());if(Duration.between(w.startedAt,Instant.now()).toMinutes()>=1){w.startedAt=Instant.now();w.count=0;}w.count++;}if(account!=null){account.failedAttemptCount++;if(account.failedAttemptCount>=5){long seconds=Math.min(1800,30L<<(Math.min(6,account.failedAttemptCount-5)));account.lockedUntil=Instant.now().plusSeconds(seconds);account.security="TEMPORARILY_LOCKED";}}}
    /** Password policy: 8 to 128 characters containing at least one uppercase letter, one lowercase letter and one digit. */
    static boolean meetsPasswordPolicy(String password){
        if(password==null||password.length()<8||password.length()>128)return false;
        boolean upper=false,lower=false,digit=false;
        for(char c:password.toCharArray()){if(c>='A'&&c<='Z')upper=true;else if(c>='a'&&c<='z')lower=true;else if(c>='0'&&c<='9')digit=true;}
        return upper&&lower&&digit;
    }
    private void validateNewPassword(Account a,String password){if(!meetsPasswordPolicy(password))throw new ApiError(400,"VALIDATION_FAILED","error.password.policy");}
    private void rememberPassword(Account a,String hash){if(hash==null)return;a.passwordHistory.addFirst(hash);while(a.passwordHistory.size()>5)a.passwordHistory.removeLast();}
    private boolean activeRole(Account a,String role){return a.roles.values().stream().anyMatch(r->r.roleCode.equals(role)&&isRoleActive(r));}
    private boolean isRoleActive(RoleAssignment role){return role.status.equals("ACTIVE")&&(role.validFrom==null||!role.validFrom.isAfter(Instant.now()))&&(role.validTo==null||role.validTo.isAfter(Instant.now()));}
    private String lowestWorkspace(Account a){if(activeRole(a,"USER"))return "USER";if(activeRole(a,"TEACHER_ADMIN")||activeRole(a,"STUDENT_ADMIN")||activeRole(a,"MEDICAL_INSURANCE_ADMIN"))return "ADMIN";if(activeRole(a,"SYSTEM_ADMIN"))return "SYSTEM_ADMIN";throw new ApiError(403,"ACCESS_DENIED","error.access.denied");}
    private String initialWorkspace(Account a,String channel){
        if ("WEB_ADMIN".equals(channel)) {
            if (activeRole(a,"SYSTEM_ADMIN")) return "SYSTEM_ADMIN";
            if (activeRole(a,"TEACHER_ADMIN")||activeRole(a,"STUDENT_ADMIN")||activeRole(a,"MEDICAL_INSURANCE_ADMIN")) return "ADMIN";
        }
        return lowestWorkspace(a);
    }
    /**
     * Resolve the external医保 role for an account. The strongest active role wins regardless of the
     * current workspace, so a dual-identity account enters the module without switching workspaces.
     */
    private String medicalInsuranceRole(Account a, String workspace){
        if(a==null||!a.lifecycle.equals("ACTIVE"))return null;
        if(activeRole(a,"SYSTEM_ADMIN"))return "SYSTEM_ADMIN";
        if(activeRole(a,"MEDICAL_INSURANCE_ADMIN"))return "MEDICAL_INSURANCE_ADMIN";
        return activeRole(a,"USER") ? "USER" : null;
    }
    private String appendTicket(String base,String secret){
        String target=Objects.requireNonNullElse(base,"/medical-insurance/sso/callback/");
        return target+(target.contains("?")?"&":"?")+"ticket="+URLEncoder.encode(secret,StandardCharsets.UTF_8);
    }
    private void requireSsoAdapter(String adapterId,String adapterSecret,String requestId){
        if(Objects.requireNonNullElse(ssoAdapterSecret,"").isBlank()
                ||!constantTimeEquals(Objects.requireNonNullElse(ssoAdapterId,""),Objects.requireNonNullElse(adapterId,""))
                ||!constantTimeEquals(Objects.requireNonNullElse(ssoAdapterSecret,""),Objects.requireNonNullElse(adapterSecret,""))){
            rejectSso(requestId,"adapter_authentication_failed");
            throw new ApiError(401,"SSO_ADAPTER_UNAUTHORIZED","error.sso.adapterUnauthorized");
        }
    }
    private void rejectSso(String requestId,String reason){append("MODULE.SSO.REJECTED",null,"SYSTEM","SSO_ADAPTER","DENY","DENIED",requestId,reason,List.of());}
    private Map<String,Object> medicalSsoPayload(Account account,Session session,String roleCode){
        List<String> roleCodes=ROLES.stream().filter(code->activeRole(account,code)).toList();
        return linked("accountId",account.id,"loginName",account.loginName,"displayName",account.displayName,"personNo",account.personNo,
                "shtongRoleCode",roleCode,"shtongRoleCodes",roleCodes,"ybRole",roleCode.equals("SYSTEM_ADMIN")?"super_admin":roleCode.equals("MEDICAL_INSURANCE_ADMIN")?"admin":"student",
                "authorizationVersion",account.authzVersion,"sessionId",session.id,"sessionExpiresAt",session.expiresAt,
                "lifecycleStatus",account.lifecycle,"isGraduated","GRADUATED".equals(account.lifecycle),
                "shtongSessionToken",session.token,"scopes",medicalSsoScopes(account,roleCode),"student",medicalSsoStudent(account));
    }
    private List<Map<String,Object>> medicalSsoScopes(Account account,String roleCode){
        if("SYSTEM_ADMIN".equals(roleCode))return List.of(linked("type","SYSTEM","code","SYSTEM","name","全校系统管理","path","全校系统管理","college",null,"grade",null,"className",null));
        if(!"MEDICAL_INSURANCE_ADMIN".equals(roleCode))return List.of();
        return account.roles.values().stream().filter(this::isRoleActive).filter(r->r.roleCode.equals("MEDICAL_INSURANCE_ADMIN"))
                .flatMap(r->r.ceilings.stream().map(orgUnits::get)).filter(Objects::nonNull).sorted(Comparator.comparing(this::orgPath))
                .map(this::medicalScopeView).toList();
    }
    private Map<String,Object> medicalScopeView(OrgUnit unit){
        List<OrgUnit> path=new ArrayList<>();Set<UUID> visited=new HashSet<>();OrgUnit current=unit;
        while(current!=null&&visited.add(current.id)){path.add(0,current);current=current.parentId==null?null:orgUnits.get(current.parentId);}
        String college=path.stream().filter(o->o.type.equals("COLLEGE")).map(o->o.name).findFirst().orElse(null);
        String grade=path.stream().filter(o->o.type.equals("GRADE")).map(o->o.name).findFirst().orElse(null);
        String className=path.stream().filter(o->o.type.equals("CLASS")).map(o->o.name).findFirst().orElse(null);
        return linked("type",unit.type,"code",unit.code,"name",unit.name,"path",String.join(" / ",path.stream().map(o->o.name).toList()),"college",college,"grade",grade,"className",className);
    }
    private Map<String,Object> medicalSsoStudent(Account account){
        if(!activeRole(account,"USER") && !"GRADUATED".equals(account.lifecycle))return null;
        Map<String,Object> result=linked("gender",account.gender,"ethnicity",account.ethnicity,"age",ageFromId(account.idCardNumber),"organizationPath",organizationPathView(account.classId),
                "idCardNumber",account.idCardNumber,"mobileNumber",account.mobileNumber,
                "currentAddress",addressView(account.currentAddress),"householdAddress",addressView(account.householdAddress));
        List<Map<String,Object>> path=organizationPathView(account.classId);
        result.put("college",path.stream().filter(item->"COLLEGE".equals(item.get("type"))).map(item->String.valueOf(item.get("name"))).findFirst().orElse(null));
        result.put("grade",path.stream().filter(item->"GRADE".equals(item.get("type"))).map(item->String.valueOf(item.get("name"))).findFirst().orElse(null));
        result.put("className",path.stream().filter(item->"CLASS".equals(item.get("type"))).map(item->String.valueOf(item.get("name"))).findFirst().orElse(null));
        return result;
    }
    /** Collects pending graduated/deleted projections. Delivery happens outside the state lock. */
    @ReadOnlyState
    public synchronized List<LifecycleProjection> pendingMedicalLifecycleProjections(int limit) {
        Instant now = Instant.now();
        return accounts.values().stream()
                .filter(a -> Set.of("DELETED", "GRADUATED").contains(a.lifecycle))
                .filter(a -> a.medicalSyncedAuthzVersion < a.authzVersion)
                .filter(a -> a.medicalSyncAfter == null || !a.medicalSyncAfter.isAfter(now))
                .sorted(Comparator.comparing(a -> a.id)).limit(limit)
                .map(account -> {
                    Map<String,Object> payload = linked("accountId",account.id,"authorizationVersion",account.authzVersion,
                            "lifecycleStatus",account.lifecycle,"isGraduated","GRADUATED".equals(account.lifecycle));
                    if ("GRADUATED".equals(account.lifecycle)) payload.putAll(linked("loginName",account.loginName,
                            "displayName",account.displayName,"personNo",account.personNo,"shtongRoleCode","USER",
                            "shtongRoleCodes",List.of("USER"),"ybRole","student","scopes",List.of(),"student",medicalSsoStudent(account)));
                    return new LifecycleProjection(account.id, account.authzVersion, payload);
                }).toList();
    }

    public synchronized void recordMedicalLifecycleProjection(UUID accountId, long authorizationVersion, boolean delivered, String requestId) {
        Account account = accounts.get(accountId);
        if (account == null) return;
        if (delivered) {
            if (account.authzVersion == authorizationVersion) { account.medicalSyncedAuthzVersion = authorizationVersion; account.medicalSyncAfter = null; }
            append("MODULE.SSO.SESSION_REVOKED",null,"SYSTEM","SSO_ADAPTER","ALLOW","SUCCESS",requestId,"lifecycle_projected:"+account.lifecycle,List.of(account.id));
        } else {
            account.medicalSyncAfter = Instant.now().plusSeconds(30);
            append("MODULE.SSO.REJECTED",null,"SYSTEM","SSO_ADAPTER","DENY","DENIED",requestId,"lifecycle_projection_retry_pending",List.of(account.id));
        }
    }

    /**
     * Drops state that can no longer be used: revoked sessions and their refresh grants, expired tickets,
     * stale idempotency records and rate windows, and one-time result files older than 24 hours.
     */
    public synchronized int pruneExpiredState() {
        Instant now = Instant.now();
        int before = sessions.size() + refreshTokens.size() + medicalSsoTickets.size() + downloadTickets.size() + idempotency.size() + loginRate.size();
        sessions.values().removeIf(s -> s.revoked && (s.revokedAt == null || s.revokedAt.isBefore(now.minus(REVOKED_SESSION_RETENTION))));
        // A consumed refresh grant is kept while its family is alive so that replay still revokes the family.
        Set<UUID> liveFamilies = sessions.values().stream().filter(s -> !s.revoked).map(s -> s.tokenFamilyId).collect(Collectors.toSet());
        refreshTokens.values().removeIf(g -> !sessions.containsKey(g.sessionToken) && !liveFamilies.contains(g.familyId));
        medicalSsoTickets.values().removeIf(t -> t.expiresAt.isBefore(now.minus(TICKET_RETENTION)));
        downloadTickets.values().removeIf(t -> t.expiresAt.isBefore(now.minus(TICKET_RETENTION)));
        idempotency.values().removeIf(r -> r.createdAt() == null || r.createdAt().isBefore(now.minus(IDEMPOTENCY_RETENTION)));
        loginRate.values().removeIf(w -> w.startedAt.isBefore(now.minus(Duration.ofHours(1))));
        Instant fileCutoff = now.minus(ONE_TIME_FILE_RETENTION);
        for (Command c : commands.values()) if (c.credentialContent != null && c.completedAt != null && c.completedAt.isBefore(fileCutoff)) c.credentialContent = null;
        for (ImportJob j : importJobs.values()) {
            Instant committed = j.committedAt == null ? j.createdAt : j.committedAt;
            if (j.credentialContent != null && committed.isBefore(fileCutoff)) j.credentialContent = null;
            if (j.createdAt.isBefore(fileCutoff) && !j.rows.isEmpty()) { if (!j.committed) j.state = "EXPIRED"; j.rows = new ArrayList<>(); }
        }
        for (Map<UUID, ExportJob> jobs : List.of(exportJobs, auditExportJobs)) for (ExportJob j : jobs.values())
            if (j.content != null && j.completedAt != null && j.completedAt.isBefore(fileCutoff)) { j.content = null; j.state = "EXPIRED"; }
        return before - (sessions.size() + refreshTokens.size() + medicalSsoTickets.size() + downloadTickets.size() + idempotency.size() + loginRate.size());
    }

    @ReadOnlyState
    public synchronized Map<String,Object> medicalReviewSubject(UUID accountId,String adapterId,String adapterSecret,String requestId) {
        requireSsoAdapter(adapterId,adapterSecret,requestId);
        Account a = accounts.get(accountId);
        if (a == null) throw new ApiError(404,"RESOURCE_NOT_FOUND","error.resource.notFound");
        append("ACCOUNT.DETAIL_VIEWED",null,"SYSTEM","SSO_ADAPTER","ALLOW","SUCCESS",requestId,"medical_auto_review_subject",List.of(accountId));
        return linked("accountId",a.id,"lifecycleStatus",a.lifecycle,"authorizationVersion",a.authzVersion,"version",a.version,
                "displayName",a.displayName,"idCardNumber",a.idCardNumber,"student",medicalSsoStudent(a));
    }

    @ReadOnlyState
    public Map<String,Object> medicalReviewDivisions(String level,String parentCode,String adapterId,String adapterSecret,String requestId) {
        requireSsoAdapter(adapterId,adapterSecret,requestId);
        if (administrativeDivisions == null) throw new ApiError(503,"REFERENCE_DATA_UNAVAILABLE","error.referenceData.unavailable");
        return Map.of("items",administrativeDivisions.list(level,parentCode));
    }

    private void executeCommand(Command c,Principal p,String requestId) {
        List<Account> targets = c.targets.stream().map(accounts::get).toList();
        if (targets.stream().anyMatch(Objects::isNull) || targets.stream().anyMatch(a->a.id.equals(p.accountId()))) throw new ApiError(409,"CONTROL_REQUEST_STALE","error.control.stale");
        boolean authorizationChanged = false;
        for (Account a : targets) {
            if (c.type.equals("DELETE") || c.type.equals("GRADUATE")) {
                a.lifecycle = c.type.equals("DELETE") ? "DELETED" : "GRADUATED";
                if (c.type.equals("GRADUATE")) { a.graduatedAt=Instant.now(); a.graduatedReason=c.reason; }
                for (RoleAssignment role : a.roles.values()) if (isRoleActive(role)) { role.status="REVOKED"; role.version++; authorizationChanged=true; }
                if (c.type.equals("DELETE")) { loginIndex.remove(normalize(a.loginName),a.id); personIndex.remove(normalize(a.personNo),a.id); }
                a.medicalSyncAfter = null;
                append(c.type.equals("DELETE")?"ACCOUNT.DELETED":"ACCOUNT.GRADUATED",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,c.reason,List.of(a.id));
            } else {
                String temp=generateTemporaryPassword(); rememberPassword(a,a.passwordHash); a.passwordHash=passwordEncoder.encode(temp);
                a.credentialState=activeRole(a,"SYSTEM_ADMIN")?"ACTIVE":"TEMPORARY"; a.credentialVersion++; c.credentials.put(a.id,new OneTimeCredential(temp));
                append("AUTH.PASSWORD.RESET",p.accountId(),p.workspace(),p.channel(),"ALLOW","SUCCESS",requestId,"changed",List.of(a.id));
            }
            a.authzVersion++; a.version++; a.updatedAt=Instant.now(); revokeAll(a.id,c.type.toLowerCase(Locale.ROOT));
        }
        if (authorizationChanged) authzRevision.incrementAndGet();
        // Plaintext passwords live only inside the one-time result file, never in the command record.
        if (c.type.equals("RESET")) { c.credentialContent=credentialCsv(c.credentials); c.credentials.clear(); }
        c.state="COMPLETED"; c.completedAt=Instant.now();
    }
    private byte[] credentialCsv(Map<UUID,OneTimeCredential> credentials){StringBuilder b=new StringBuilder("登录账号,姓名,初始密码\r\n");credentials.forEach((id,value)->{Account a=accounts.get(id);b.append(csv(a==null?"":a.loginName)).append(',').append(csv(a==null?"":a.displayName)).append(',').append(csv(value.temporaryPassword())).append("\r\n");});return withUtf8Bom(b.toString());}
    private Map<String,Object> issueDownloadTicket(Principal p,String type,UUID artifactId){String secret=randomToken();DownloadTicket ticket=new DownloadTicket(hashToken(secret),p.accountId(),type,artifactId);downloadTickets.put(ticket.id,ticket);return Map.of("url","/v1/system/downloads/"+ticket.id+"?token="+secret,"expiresAt",ticket.expiresAt,"singleUse",true);}
    private String randomToken(){byte[] value=new byte[32];secureRandom.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private String hashToken(String token){return sha256(refreshTokenPepper+":"+Objects.requireNonNullElse(token,""));}
    private String sha256(String value){return HexFormat.of().formatHex(sha256Bytes(value));}
    private String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
    private byte[] sha256Bytes(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean constantTimeEquals(String left,String right){return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),right.getBytes(StandardCharsets.US_ASCII));}
    private void revokeSession(Session session){if(!session.revoked){session.revoked=true;session.revokedAt=Instant.now();}}
    private void revokeFamily(UUID family){refreshTokens.values().stream().filter(g->g.familyId.equals(family)).forEach(g->g.revoked=true);sessions.values().stream().filter(s->s.tokenFamilyId.equals(family)).forEach(this::revokeSession);}
    private String cookie(HttpServletRequest req,String name){if(req.getCookies()!=null)for(var c:req.getCookies())if(c.getName().equals(name))return c.getValue();return null;}
    private Map<String,Object> accountView(Account a){Map<String,Object> m=new LinkedHashMap<>(summary(a));m.put("classId",a.classId);m.put("organizationPath",organizationPathView(a.classId));m.put("idCardNumber",hasStudentRecord(a)?a.idCardNumber:null);m.put("mobileNumber",hasStudentRecord(a)?a.mobileNumber:null);m.put("currentAddress",hasStudentRecord(a)?addressView(a.currentAddress):null);m.put("householdAddress",hasStudentRecord(a)?addressView(a.householdAddress):null);m.put("profileCompleted",a.profileCompletedAt!=null);m.put("lastLoginAt",a.lastLoginAt);m.put("createdAt",a.createdAt);m.put("updatedAt",a.updatedAt);return m;}
    private Map<String,Object> profileView(Account a){
        boolean student=activeRole(a,"USER");
        return linked("accountId",a.id,"displayName",a.displayName,"personNo",a.personNo,"gender",student?a.gender:null,"ethnicity",student?a.ethnicity:null,"age",student?ageFromId(a.idCardNumber):null,
                "roleCodes",a.roles.values().stream().filter(this::isRoleActive).map(r->r.roleCode).distinct().toList(),
                "organizationPath",student?organizationPathView(a.classId):List.of(),"counselors",student?counselorViews(a):List.of(),
                "managedScopes",managedScopeViews(a),"profileCompletionRequired",student&&a.profileCompletedAt==null,
                "idCardNumberMasked",student&&a.idCardNumber!=null?maskIdCard(a.idCardNumber):null,
                "mobileNumberMasked",student&&a.mobileNumber!=null?maskMobile(a.mobileNumber):null,
                "currentAddressMasked",student&&a.currentAddress!=null?maskAddress(a.currentAddress):null,
                "householdAddressMasked",student&&a.householdAddress!=null?maskAddress(a.householdAddress):null,"version",a.version);
    }
    private List<Map<String,Object>> organizationPathView(UUID leafId){
        if(leafId==null)return List.of();
        LinkedList<Map<String,Object>> path=new LinkedList<>();Set<UUID> visited=new HashSet<>();OrgUnit current=orgUnits.get(leafId);
        while(current!=null&&visited.add(current.id)){path.addFirst(linked("type",current.type,"code",current.code,"name",current.name));current=current.parentId==null?null:orgUnits.get(current.parentId);}
        return List.copyOf(path);
    }
    private List<Map<String,Object>> counselorViews(Account student){
        if(student.classId==null)return List.of();
        return accounts.values().stream().filter(a->a.lifecycle.equals("ACTIVE")&&activeRole(a,"TEACHER_ADMIN"))
                .filter(a->a.roles.values().stream().filter(this::isRoleActive).filter(r->r.roleCode.equals("TEACHER_ADMIN")).anyMatch(r->r.ceilings.stream().anyMatch(ceiling->isDescendant(ceiling,student.classId))))
                .sorted(Comparator.comparing((Account a)->a.displayName).thenComparing(a->a.personNo)).map(a->Map.<String,Object>of("displayName",a.displayName,"personNo",a.personNo)).toList();
    }
    private List<Map<String,Object>> managedScopeViews(Account a){
        List<Map<String,Object>> result=new ArrayList<>();
        for(RoleAssignment assignment:a.roles.values().stream().filter(this::isRoleActive).toList()){
            if(assignment.roleCode.equals("USER"))continue;
            List<Map<String,Object>> scopes=assignment.roleCode.equals("SYSTEM_ADMIN")
                    ?List.of(Map.of("type","SYSTEM","name","全校系统管理","path","全校系统管理"))
                    :assignment.ceilings.stream().map(orgUnits::get).filter(Objects::nonNull).sorted(Comparator.comparing(this::orgPath)).map(unit->Map.<String,Object>of("type",unit.type,"name",unit.name,"path",orgPath(unit))).toList();
            result.add(Map.of("roleCode",assignment.roleCode,"scopes",scopes));
        }
        return List.copyOf(result);
    }
    private Map<String,Object> exportStatusView(ExportJob j){return linked("id",j.id,"state",j.state,"resultCount",j.resultCount,"failureCode",null,"createdAt",j.createdAt,"completedAt",j.completedAt);}
    private byte[] withUtf8Bom(String value){byte[] raw=value.getBytes(StandardCharsets.UTF_8);byte[] result=new byte[raw.length+3];result[0]=(byte)0xEF;result[1]=(byte)0xBB;result[2]=(byte)0xBF;System.arraycopy(raw,0,result,3,raw.length);return result;}
    private String csv(Object raw){String value=Objects.toString(raw,"");if(!value.isEmpty()&&"=+-@\t\r".indexOf(value.charAt(0))>=0)value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
    private String normalizeGender(String value){if(value==null||value.isBlank())return null;String normalized=value.trim().toUpperCase(Locale.ROOT);if(!GENDERS.contains(normalized))throw new ApiError(400,"VALIDATION_FAILED","error.account.genderInvalid");return normalized;}
    private String normalizeEthnicity(String value){String normalized=Objects.requireNonNullElse(value,"").trim();if(!ETHNICITIES.contains(normalized))throw new ApiError(400,"VALIDATION_FAILED","error.account.ethnicityInvalid");return normalized;}
    private Integer ageFromId(String id){if(id==null||id.length()<14)return null;try{LocalDate birth=LocalDate.of(Integer.parseInt(id.substring(6,10)),Integer.parseInt(id.substring(10,12)),Integer.parseInt(id.substring(12,14)));LocalDate today=LocalDate.now();return Period.between(birth,today).getYears();}catch(Exception ignored){return null;}}
    private String profileRestriction(Account account){return activeRole(account,"USER")&&account.profileCompletedAt==null?"PROFILE_COMPLETION_ONLY":"NORMAL";}
    private Principal profileCompletionPrincipal(HttpServletRequest request,String requestId){Principal principal=principal(request,null,true,requestId);if(principal.restriction().equals("PASSWORD_CHANGE_ONLY"))throw new ApiError(401,"PASSWORD_CHANGE_REQUIRED","error.password.changeRequired");return principal;}
    private AdministrativeDivisionService divisionService(){if(administrativeDivisions==null)administrativeDivisions=new AdministrativeDivisionService(new com.fasterxml.jackson.databind.ObjectMapper(),"https://dmfw.mca.gov.cn/9095/xzqh/getList",java.net.http.HttpClient.newHttpClient());return administrativeDivisions;}
    private Address resolveAddress(Map<String,String> source){if(source==null)throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");var value=divisionService().resolve(source.get("provinceCode"),source.get("cityCode"),source.get("districtCode"),source.get("detail"));Address address=new Address();address.provinceCode=value.provinceCode();address.provinceName=value.provinceName();address.cityCode=value.cityCode();address.cityName=value.cityName();address.districtCode=value.districtCode();address.districtName=value.districtName();address.detail=value.detail();return address;}
    private String normalizeIdCard(String value){return Objects.requireNonNullElse(value,"").trim().toUpperCase(Locale.ROOT);}
    private boolean validIdCard(String value){if(!ID_CARD.matcher(value).matches())return false;int[] weights={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};char[] checks={'1','0','X','9','8','7','6','5','4','3','2'};int sum=0;for(int i=0;i<17;i++)sum+=(value.charAt(i)-'0')*weights[i];if(checks[sum%11]!=value.charAt(17))return false;try{int year=Integer.parseInt(value.substring(6,10)),month=Integer.parseInt(value.substring(10,12)),day=Integer.parseInt(value.substring(12,14));LocalDate birth=LocalDate.of(year,month,day);return !birth.isAfter(LocalDate.now())&&birth.isAfter(LocalDate.of(1900,1,1));}catch(Exception ignored){return false;}}
    private Map<String,Object> sensitiveChange(String field){return linked("field",field,"classification","RESTRICTED_PERSONAL","beforePresent",false,"afterPresent",true);}
    private Map<String,Object> sensitiveUpdate(String field,boolean beforePresent){return linked("field",field,"classification","RESTRICTED_PERSONAL","beforePresent",beforePresent,"afterPresent",true);}
    private boolean sameAddress(Address left,Address right){if(left==right)return true;if(left==null||right==null)return false;return Objects.equals(left.provinceCode,right.provinceCode)&&Objects.equals(left.cityCode,right.cityCode)&&Objects.equals(left.districtCode,right.districtCode)&&Objects.equals(left.detail,right.detail);}
    private Map<String,Object> addressView(Address value){return value==null?null:linked("provinceCode",value.provinceCode,"provinceName",value.provinceName,"cityCode",value.cityCode,"cityName",value.cityName,"districtCode",value.districtCode,"districtName",value.districtName,"detail",value.detail);}
    private String maskIdCard(String value){return value.length()==18?value.substring(0,6)+"********"+value.substring(14):"********";}
    private String maskMobile(String value){return value.length()==11?value.substring(0,3)+"****"+value.substring(7):"********";}
    private String maskAddress(Address value){return value.provinceName+value.cityName+value.districtName+"********";}
    private String fullAddress(Address value){return value.provinceName+value.cityName+value.districtName+value.detail;}
    private Map<String,Object> change(String field,Object before,Object after){return linked("field",field,"classification","INTERNAL","before",before,"after",after);}
    private UUID parseUuid(String value){try{return value==null?null:UUID.fromString(value);}catch(Exception e){return null;}}
    private UUID uuidValue(Object value){if(value instanceof UUID id)return id;return value==null?null:parseUuid(String.valueOf(value));}
    private Instant parseInstant(String value,Instant fallback){try{return value==null?fallback:Instant.parse(value);}catch(Exception e){throw new ApiError(400,"VALIDATION_FAILED","error.validation.failed");}}
    private boolean accountWithinOrg(Account a,UUID org){if(org==null)return false;if(isDescendant(org,a.classId))return true;return a.roles.values().stream().filter(this::isRoleActive).anyMatch(r->r.ceilings.stream().anyMatch(scope->isDescendant(org,scope)));}
    private boolean isDescendant(UUID ancestor,UUID candidate){Set<UUID> visited=new HashSet<>();UUID current=candidate;while(current!=null&&visited.add(current)){if(current.equals(ancestor))return true;OrgUnit node=orgUnits.get(current);current=node==null?null:node.parentId;}return false;}
    private record Cursor(Instant createdAt,UUID id){}
    private record AuditCursor(Instant occurredAt,UUID id){}
    private record ControlCursor(Instant requestedAt,UUID id){}
    private String encodeCursor(Account a){return Base64.getUrlEncoder().withoutPadding().encodeToString((a.createdAt+"|"+a.id).getBytes(StandardCharsets.UTF_8));}
    private Cursor decodeStableCursor(String raw){if(raw==null||raw.isBlank())return null;try{String[] parts=new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8).split("\\|",2);return new Cursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));}catch(Exception e){throw new ApiError(400,"VALIDATION_FAILED","error.cursor.invalid");}}
    private String encodeAuditCursor(AuditEvent event){return Base64.getUrlEncoder().withoutPadding().encodeToString((event.occurredAt+"|"+event.id).getBytes(StandardCharsets.UTF_8));}
    private AuditCursor decodeAuditCursor(String raw){if(raw==null||raw.isBlank())return null;try{String[] parts=new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8).split("\\|",2);return new AuditCursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));}catch(Exception e){throw new ApiError(400,"VALIDATION_FAILED","error.cursor.invalid");}}
    private String encodeControlCursor(ControlRequest request){return Base64.getUrlEncoder().withoutPadding().encodeToString((request.requestedAt+"|"+request.id).getBytes(StandardCharsets.UTF_8));}
    private ControlCursor decodeControlCursor(String raw){if(raw==null||raw.isBlank())return null;try{String[] parts=new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8).split("\\|",2);return new ControlCursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));}catch(Exception e){throw new ApiError(400,"VALIDATION_FAILED","error.cursor.invalid");}}
    @SuppressWarnings("unchecked") private Map<String,Object> asMap(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:null;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> castMaps(Object value){return value instanceof List<?> list?(List<Map<String,Object>>)(List<?>)list:List.of();}
    private List<AuditEvent> filterAuditForExport(Map<String,Object> filter){Instant from=parseInstant(filter.get("occurredFrom") instanceof String s?s:null,Instant.EPOCH);Instant to=parseInstant(filter.get("occurredTo") instanceof String s?s:null,Instant.now().plusSeconds(1));Set<String> codes=filter.get("eventCodes") instanceof Collection<?> c?c.stream().map(String::valueOf).collect(Collectors.toSet()):Set.of();UUID actor=uuidValue(filter.get("actorAccountId"));UUID subject=uuidValue(filter.get("subjectAccountId"));return auditEvents.values().stream().filter(e->!e.occurredAt.isBefore(from)&&e.occurredAt.isBefore(to)).filter(e->codes.isEmpty()||codes.contains(e.code)).filter(e->actor==null||Objects.equals(e.actor,actor)).filter(e->subject==null||e.subjects.contains(subject)).sorted(Comparator.comparing((AuditEvent e)->e.occurredAt).thenComparing(e->e.id)).toList();}
    private byte[] auditCsv(List<AuditEvent> events,String purpose,UUID jobId){StringBuilder b=new StringBuilder("# 导出编号="+jobId+"; 用途="+csv(purpose)+"; 生成时间="+Instant.now()+"\r\n");b.append("记录编号,操作,操作时间,操作人编号,权限校验,操作结果,请求编号,涉及人数,完整性校验值\r\n");for(AuditEvent e:events)b.append(csv(e.id)).append(',').append(csv(Presentation.label(e.code))).append(',').append(csv(e.occurredAt)).append(',').append(csv(e.actor)).append(',').append(csv(Presentation.label(e.decision))).append(',').append(csv(Presentation.label(e.outcome))).append(',').append(csv(e.requestId)).append(',').append(csv(e.subjects.size())).append(',').append(csv(e.payloadHash)).append("\r\n");return withUtf8Bom(b.toString());}
    private boolean blankRow(Row row) { if (row == null) return true; for (Cell value : row) if (!cell(row,value.getColumnIndex()).isBlank()) return false; return true; }
    private void validateHeaders(Sheet sheet, List<String> headers, ImportJob job) { Row row=sheet.getRow(0); if(row==null||row.getLastCellNum()!=headers.size()){job.errors.add(importError(sheet.getSheetName(),1,"sheet","INVALID_HEADERS"));return;}for(int i=0;i<headers.size();i++)if(!headers.get(i).equals(cell(row,i)))job.errors.add(importError(sheet.getSheetName(),1,headers.get(i),"INVALID_HEADERS")); }
    private void validateLegacyHeaders(Workbook wb, ImportJob job) {
        validateHeaders(wb.getSheet("accounts"),List.of("row_key","login_name","display_name","person_no","class_code"),job);
        validateHeaders(wb.getSheet("role_assignments"),List.of("row_key","role_code"),job);
        validateHeaders(wb.getSheet("assignment_scopes"),List.of("row_key","role_code","org_unit_code"),job);
    }
    private OrgUnit resolveImportOrg(String value, String type, int row, String field, ImportJob job) {
        if(value.isBlank())return null;
        List<OrgUnit> candidates=orgUnits.values().stream().filter(o->o.status.equals("ACTIVE")&&(type==null||o.type.equals(type))).toList();
        List<OrgUnit> matches=candidates.stream().filter(o->o.code.equalsIgnoreCase(value)||orgPath(o).equals(value)).toList();
        if(matches.isEmpty())matches=candidates.stream().filter(o->o.name.equals(value)).toList();
        if(matches.size()!=1){job.errors.add(importError(Presentation.template("sheet"),row,field,matches.isEmpty()?"UNKNOWN_ORG":"AMBIGUOUS_ORG"));return null;}
        return matches.getFirst();
    }
    private void parseChineseWorkbook(Workbook wb, ImportJob job) {
        Sheet sheet=wb.getSheet(Presentation.template("sheet"));List<String> currentHeaders=Presentation.headers(),legacyHeaders=List.of("登录账号","姓名","学工号","所属班级","身份","管理范围");
        Row header=sheet.getRow(0);boolean legacy=header!=null&&header.getLastCellNum()==legacyHeaders.size()&&java.util.stream.IntStream.range(0,legacyHeaders.size()).allMatch(index->legacyHeaders.get(index).equals(cell(header,index)));
        job.templateVersion=legacy?"v2-zh":Presentation.template("version");
        if(!legacy)validateHeaders(sheet,currentHeaders,job);if(!job.errors.isEmpty())return;
        Set<String> logins=new HashSet<>(),persons=new HashSet<>();
        for(int i=1;i<=sheet.getLastRowNum();i++){
            Row row=sheet.getRow(i);if(blankRow(row))continue;
            int line=i+1;String login=cell(row,0),display=cell(row,1),person=cell(row,2),genderText=legacy?"":cell(row,3),classText=cell(row,legacy?3:4),roleText=cell(row,legacy?4:5),scopeText=cell(row,legacy?5:6);
            String role=INITIAL_ROLES.stream().filter(r->Presentation.label(r).equals(roleText)||r.equals(roleText)).findFirst().orElse("");
            String gender=GENDERS.stream().filter(value->Presentation.label(value).equals(genderText)||value.equalsIgnoreCase(genderText)).findFirst().orElse("");
            if(!LOGIN.matcher(login).matches())job.errors.add(importError(sheet.getSheetName(),line,"登录账号","INVALID"));
            if(display.isBlank()||display.length()>100)job.errors.add(importError(sheet.getSheetName(),line,"姓名","INVALID"));
            if(person.isBlank()||person.length()>100)job.errors.add(importError(sheet.getSheetName(),line,"学工号","INVALID"));
            if(!genderText.isBlank()&&gender.isBlank())job.errors.add(importError(sheet.getSheetName(),line,"性别","INVALID"));
            if(!logins.add(normalize(login))||loginIndex.containsKey(normalize(login)))job.errors.add(importError(sheet.getSheetName(),line,"登录账号","DUPLICATE"));
            if(!persons.add(normalize(person))||personIndex.containsKey(normalize(person)))job.errors.add(importError(sheet.getSheetName(),line,"学工号","DUPLICATE"));
            if(role.isBlank())job.errors.add(importError(sheet.getSheetName(),line,"身份","INVALID"));
            OrgUnit classroom=resolveImportOrg(classText,"CLASS",line,"所属班级",job);
            if(role.equals("USER")&&classText.isBlank())job.errors.add(importError(sheet.getSheetName(),line,"所属班级","CLASS_REQUIRED"));
            if(!role.equals("USER")&&!genderText.isBlank())job.errors.add(importError(sheet.getSheetName(),line,"性别","INVALID"));
            List<Map<String,Object>> ceilings=new ArrayList<>();Set<UUID> seen=new HashSet<>();
            for(String scope:scopeText.split("[;；\\r\\n]+")) { if(scope.isBlank())continue;OrgUnit unit=resolveImportOrg(scope.trim(),null,line,"管理范围",job);if(unit==null)continue;
                if(role.equals("TEACHER_ADMIN")&&!unit.type.equals("GRADE")||role.equals("STUDENT_ADMIN")&&!Set.of("COLLEGE","GRADE","CLASS").contains(unit.type)||role.equals("USER"))job.errors.add(importError(sheet.getSheetName(),line,"管理范围","ROLE_SCOPE_TYPE_INVALID"));
                if(!seen.add(unit.id))job.errors.add(importError(sheet.getSheetName(),line,"管理范围","DUPLICATE"));else ceilings.add(Map.of("orgUnitId",unit.id));
            }
            if(Set.of("TEACHER_ADMIN","STUDENT_ADMIN").contains(role)&&scopeText.isBlank())job.errors.add(importError(sheet.getSheetName(),line,"管理范围","REQUIRED"));
            job.rows.add(linked("row_key",String.valueOf(line),"login_name",login,"display_name",display,"person_no",person,"gender",gender.isBlank()?null:gender,"class_code",classroom==null?"":classroom.code,"class_id",classroom==null?null:classroom.id,"role_assignments",List.of(Map.of("roleCode",role,"scopeCeilings",ceilings))));
            if(job.rows.size()>5000){job.errors.add(importError(sheet.getSheetName(),line,"登录账号","ROW_LIMIT_EXCEEDED"));break;}
        }
    }
    private void parseImportWorkbook(Workbook wb,ImportJob job){
        Sheet accountsSheet=wb.getSheet("accounts"),rolesSheet=wb.getSheet("role_assignments"),scopesSheet=wb.getSheet("assignment_scopes");
        Map<String,Map<String,Object>> accountRows=new LinkedHashMap<>();Set<String> logins=new HashSet<>(),persons=new HashSet<>();
        for(int i=1;i<=accountsSheet.getLastRowNum();i++){
            Row row=accountsSheet.getRow(i);if(blankRow(row))continue;
            String rowKey=cell(row,0),login=cell(row,1),display=cell(row,2),person=cell(row,3),classCode=cell(row,4);Map<String,Object> data=new LinkedHashMap<>();
            data.put("row_key",rowKey);data.put("login_name",login);data.put("display_name",display);data.put("person_no",person);data.put("gender",null);data.put("class_code",classCode);data.put("_source_row",i+1);
            if(rowKey.isBlank())job.errors.add(importError("accounts",i+1,"row_key","REQUIRED"));
            if(accountRows.putIfAbsent(rowKey,data)!=null)job.errors.add(importError("accounts",i+1,"row_key","DUPLICATE"));
            if(login.isBlank()||!LOGIN.matcher(login).matches())job.errors.add(importError("accounts",i+1,"login_name","INVALID"));
            if(display.isBlank())job.errors.add(importError("accounts",i+1,"display_name","REQUIRED"));
            if(person.isBlank())job.errors.add(importError("accounts",i+1,"person_no","REQUIRED"));
            if(!logins.add(normalize(login))||loginIndex.containsKey(normalize(login)))job.errors.add(importError("accounts",i+1,"login_name","DUPLICATE"));
            if(!persons.add(normalize(person))||personIndex.containsKey(normalize(person)))job.errors.add(importError("accounts",i+1,"person_no","DUPLICATE"));
        }
        if(accountRows.size()>5000)job.errors.add(importError("accounts",5001,"row_key","ROW_LIMIT_EXCEEDED"));
        Map<String,List<String>> roleCodes=new LinkedHashMap<>();Set<String> roleKeys=new HashSet<>();
        for(int i=1;i<=rolesSheet.getLastRowNum();i++){
            Row row=rolesSheet.getRow(i);if(blankRow(row))continue;String rowKey=cell(row,0),role=cell(row,1),roleKey=rowKey+"|"+role;
            if(!accountRows.containsKey(rowKey))job.errors.add(importError("role_assignments",i+1,"row_key","UNKNOWN_ROW"));
            else if(!INITIAL_ROLES.contains(role))job.errors.add(importError("role_assignments",i+1,"role_code","INVALID"));
            else if(!roleKeys.add(roleKey))job.errors.add(importError("role_assignments",i+1,"role_code","DUPLICATE"));
            else roleCodes.computeIfAbsent(rowKey,k->new ArrayList<>()).add(role);
        }
        Map<String,List<Map<String,Object>>> scopes=new LinkedHashMap<>();Set<String> scopeKeys=new HashSet<>();
        for(int i=1;i<=scopesSheet.getLastRowNum();i++){
            Row row=scopesSheet.getRow(i);if(blankRow(row))continue;String rowKey=cell(row,0),role=cell(row,1),code=cell(row,2),assignmentKey=rowKey+"|"+role;
            OrgUnit org=orgUnits.values().stream().filter(o->o.status.equals("ACTIVE")&&o.code.equalsIgnoreCase(code)).findFirst().orElse(null);
            if(!accountRows.containsKey(rowKey))job.errors.add(importError("assignment_scopes",i+1,"row_key","UNKNOWN_ROW"));
            else if(!roleKeys.contains(assignmentKey))job.errors.add(importError("assignment_scopes",i+1,"role_code","UNKNOWN_ROLE_ASSIGNMENT"));
            else if(org==null)job.errors.add(importError("assignment_scopes",i+1,"org_unit_code","UNKNOWN_ORG"));
            else if(role.equals("TEACHER_ADMIN")&&!org.type.equals("GRADE")||role.equals("STUDENT_ADMIN")&&!Set.of("COLLEGE","GRADE","CLASS").contains(org.type)||role.equals("USER"))job.errors.add(importError("assignment_scopes",i+1,"org_unit_code","ROLE_SCOPE_TYPE_INVALID"));
            else if(!scopeKeys.add(assignmentKey+"|"+org.id))job.errors.add(importError("assignment_scopes",i+1,"org_unit_code","DUPLICATE"));
            else scopes.computeIfAbsent(assignmentKey,k->new ArrayList<>()).add(Map.of("orgUnitId",org.id));
        }
        for(Map<String,Object> data:accountRows.values()){
            String rowKey=String.valueOf(data.get("row_key"));int sourceRow=((Number)data.get("_source_row")).intValue();List<String> roles=roleCodes.getOrDefault(rowKey,List.of());
            if(roles.isEmpty()){job.errors.add(importError("role_assignments",sourceRow,"role_code","REQUIRED"));continue;}
            List<Map<String,Object>> assignments=new ArrayList<>();
            for(String role:roles){List<Map<String,Object>> ceilings=scopes.getOrDefault(rowKey+"|"+role,List.of());if(Set.of("TEACHER_ADMIN","STUDENT_ADMIN").contains(role)&&ceilings.isEmpty())job.errors.add(importError("assignment_scopes",sourceRow,"org_unit_code","REQUIRED"));assignments.add(Map.of("roleCode",role,"scopeCeilings",ceilings));}
            String classCode=String.valueOf(data.get("class_code"));UUID classId=orgUnits.values().stream().filter(o->o.type.equals("CLASS")&&o.status.equals("ACTIVE")&&o.code.equalsIgnoreCase(classCode)).map(o->o.id).findFirst().orElse(null);
            if(roles.contains("USER")&&classId==null)job.errors.add(importError("accounts",sourceRow,"class_code","CLASS_REQUIRED"));else if(!classCode.isBlank()&&classId==null)job.errors.add(importError("accounts",sourceRow,"class_code","UNKNOWN_ORG"));
            data.remove("_source_row");data.put("class_id",classId);data.put("role_assignments",assignments);job.rows.add(data);
        }
    }
}
