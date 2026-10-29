package com.shtong.gateway;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link AccountAccessService} operation that changes nothing except the audit ledger.
 * Production persistence appends its audit rows but skips the state checkpoint.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface ReadOnlyState {}
