package com.shtong.gateway;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Executes only when deployment injects all four one-time bootstrap values. */
@Component
public class BootstrapRunner implements ApplicationRunner {
    private final AccountAccessService service;
    private final Environment environment;
    public BootstrapRunner(AccountAccessService service, Environment environment) { this.service = service; this.environment = environment; }
    @Override public void run(ApplicationArguments args) {
        String firstLogin=value("SHTONG_BOOTSTRAP_ADMIN_ONE_LOGIN"),firstPassword=value("SHTONG_BOOTSTRAP_ADMIN_ONE_PASSWORD");
        String secondLogin=value("SHTONG_BOOTSTRAP_ADMIN_TWO_LOGIN"),secondPassword=value("SHTONG_BOOTSTRAP_ADMIN_TWO_PASSWORD");
        boolean requested=firstLogin!=null||firstPassword!=null||secondLogin!=null||secondPassword!=null;
        if(!requested)return;
        if(firstLogin==null||firstPassword==null||secondLogin==null||secondPassword==null)throw new IllegalStateException("All four initial-administrator bootstrap values are required together");
        boolean development=Boolean.parseBoolean(environment.getProperty("shtong.dev-mode","false"));
        String bootstrapSecret=environment.getProperty("shtong.bootstrap-secret","");
        if(!development&&bootstrapSecret.length()<32)throw new IllegalStateException("Production bootstrap requires SHTONG_BOOTSTRAP_SECRET with at least 32 characters");
        service.bootstrapInitialAdmins(firstLogin,firstPassword,secondLogin,secondPassword);
    }

    private String value(String name){String value=environment.getProperty(name,System.getenv(name));return value==null||value.isBlank()?null:value;}
}
