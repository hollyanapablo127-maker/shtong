package com.shtong.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AccountAccessService audit;
    public ApiExceptionHandler(AccountAccessService audit){this.audit=audit;}

    @ExceptionHandler(ApiError.class)
    ResponseEntity<ErrorResponse> handle(ApiError e, HttpServletRequest req) {
        String requestId = requestId(req);
        audit.recordFailure(req,e.code(),e.status(),requestId);
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-Id", requestId)
                .body(new ErrorResponse(e.code(), e.messageKey(), requestId, e.fieldErrors()));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ErrorResponse> unknownRoute(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        boolean method = e instanceof HttpRequestMethodNotSupportedException;
        return ResponseEntity.status(method ? 405 : 404).contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-Id", requestId)
                .body(new ErrorResponse(method ? "METHOD_NOT_ALLOWED" : "RESOURCE_NOT_FOUND", method ? "error.method.notAllowed" : "error.resource.notFound", requestId, List.of()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ErrorResponse> validation(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        audit.recordFailure(req,"VALIDATION_FAILED",400,requestId);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-Id", requestId)
                .body(new ErrorResponse("VALIDATION_FAILED", "error.validation.failed", requestId, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest req) {
        String requestId = requestId(req);
        LOG.error("Unhandled API exception requestId={} method={} path={}", requestId, req.getMethod(), req.getRequestURI(), e);
        audit.recordFailure(req,"INTERNAL_ERROR",500,requestId);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).header("X-Request-Id", requestId)
                .body(new ErrorResponse("INTERNAL_ERROR", "error.internal", requestId, List.of()));
    }

    private String requestId(HttpServletRequest req) {
        return RequestIds.resolve(req);
    }

    public record ErrorResponse(String code, String messageKey, String requestId,
                                List<ApiError.FieldError> fieldErrors) {}
}
