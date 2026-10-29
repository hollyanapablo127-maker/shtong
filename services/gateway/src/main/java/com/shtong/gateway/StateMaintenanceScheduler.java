package com.shtong.gateway;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically drops expired sessions, tickets, idempotency records and one-time result files. */
@Component
final class StateMaintenanceScheduler {
    private final AccountAccessService service;

    StateMaintenanceScheduler(AccountAccessService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${shtong.maintenance.prune-delay-ms:600000}", initialDelay = 60000)
    public void prune() { service.pruneExpiredState(); }
}
