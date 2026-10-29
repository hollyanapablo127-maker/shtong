package com.shtong.gateway;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Loads and verifies the persisted state and the complete audit chain before the gateway serves traffic. */
@Component
@Profile("production")
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ProductionStateRestorer implements ApplicationRunner {
    private final AccountAccessService service;
    private final ProductionStateTransactionAspect persistence;
    ProductionStateRestorer(AccountAccessService service,ProductionStateTransactionAspect persistence,@Value("${shtong.refresh-token-pepper}")String refreshPepper){this.service=service;this.persistence=persistence;if(refreshPepper==null||refreshPepper.length()<32)throw new IllegalStateException("SHTONG_REFRESH_TOKEN_PEPPER must contain at least 32 characters");}
    @Override public void run(ApplicationArguments args){persistence.synchronizeNow(service);}
}
