package com.shtong.gateway;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.List;

/**
 * Wraps every public {@link AccountAccessService} operation in one database transaction.
 *
 * <p>Per call the cost is proportional to the work done, not to the size of the ledger: the advisory lock
 * serializes instances, a cheap sync point detects changes made elsewhere, only the operation's new audit rows
 * are written, and the encrypted checkpoint is rewritten only when the domain state actually changed.
 * Business rejections ({@link ApiError}) commit their denial audit; any other failure rolls back and reloads.
 */
@Aspect
@Component
@Profile("production")
final class ProductionStateTransactionAspect {
    private final PostgresRuntimeStateRepository repository;
    private final TransactionTemplate transactions;
    private final Object lock = new Object();
    // Guarded by lock.
    private long stateVersion = -1;
    private long auditSequence = -1;
    private byte[] stateDigest;

    private record Outcome(Object result, ApiError rejection) {}

    ProductionStateTransactionAspect(PostgresRuntimeStateRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Around("execution(public * com.shtong.gateway.AccountAccessService.*(..))")
    Object checkpoint(ProceedingJoinPoint invocation) {
        AccountAccessService service = (AccountAccessService) invocation.getTarget();
        boolean readOnly = ((MethodSignature) invocation.getSignature()).getMethod().isAnnotationPresent(ReadOnlyState.class);
        synchronized (lock) {
            Outcome outcome;
            try {
                outcome = transactions.execute(status -> run(service, invocation, readOnly));
            } catch (RuntimeException | Error failure) {
                reload(service);
                throw failure;
            }
            if (outcome.rejection() != null) throw outcome.rejection();
            return outcome.result();
        }
    }

    /** Loads the persisted state and ledger; used at startup. */
    void synchronizeNow(AccountAccessService service) {
        synchronized (lock) {
            transactions.executeWithoutResult(status -> { repository.lock(); synchronize(service); });
        }
    }

    private Outcome run(AccountAccessService service, ProceedingJoinPoint invocation, boolean readOnly) {
        repository.lock();
        synchronize(service);
        int mark = service.auditMark();
        Object result = null;
        ApiError rejection = null;
        try {
            result = invocation.proceed();
        } catch (ApiError error) {
            rejection = error;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
        persist(service, mark, readOnly);
        return new Outcome(result, rejection);
    }

    private void synchronize(AccountAccessService service) {
        PostgresRuntimeStateRepository.SyncPoint point = repository.syncPoint();
        if (point.stateVersion() != stateVersion) {
            var checkpoint = repository.load();
            if (checkpoint.isPresent()) {
                service.restoreState(checkpoint.get().payload());
                stateDigest = sha256(checkpoint.get().payload());
            } else {
                service.resetState();
                stateDigest = null;
            }
            stateVersion = point.stateVersion();
        }
        if (point.auditSequence() != auditSequence) {
            if (auditSequence < 0 || point.auditSequence() < auditSequence) service.replaceAuditLedger(repository.auditAfter(0));
            else service.extendAuditLedger(repository.auditAfter(auditSequence));
            auditSequence = point.auditSequence();
        }
    }

    private void persist(AccountAccessService service, int mark, boolean readOnly) {
        List<AccountAccessService.AuditLedgerRecord> fresh = service.auditRecordsSince(mark);
        if (!fresh.isEmpty()) auditSequence = repository.appendAudit(fresh);
        if (readOnly) return;
        byte[] state = service.captureState();
        byte[] digest = sha256(state);
        if (stateDigest == null || !MessageDigest.isEqual(digest, stateDigest)) {
            stateVersion = repository.save(state);
            stateDigest = digest;
        }
    }

    /** After a rollback the in-memory state may be ahead of the database; discard it and load the committed state. */
    private void reload(AccountAccessService service) {
        stateVersion = -1;
        auditSequence = -1;
        stateDigest = null;
        transactions.executeWithoutResult(status -> { repository.lock(); synchronize(service); });
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
