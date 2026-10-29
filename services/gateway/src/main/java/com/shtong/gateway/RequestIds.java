package com.shtong.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;
import java.util.regex.Pattern;

/** Resolves one request id per HTTP request so responses, errors and audit rows always agree. */
final class RequestIds {
    static final String ATTRIBUTE = RequestIds.class.getName();
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private RequestIds() {}

    static String resolve(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String value) return value;
        String supplied = request.getHeader("X-Request-Id");
        String value = supplied != null && ACCEPTED.matcher(supplied).matches() ? supplied : "req_" + UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, value);
        return value;
    }

    static String current(String supplied) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        Object value = attributes == null ? null : attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (value instanceof String resolved) return resolved;
        return supplied != null && ACCEPTED.matcher(supplied).matches() ? supplied : "req_" + UUID.randomUUID();
    }
}
