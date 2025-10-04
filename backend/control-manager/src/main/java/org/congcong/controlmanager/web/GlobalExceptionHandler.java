package org.congcong.controlmanager.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private String rid() {
        String xid = MDC.get(RequestIdFilter.HEADER);
        if (xid == null) {
            return "";
        }
        return xid;
    }

    private ResponseEntity<Map<String,Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message,
                "requestId", rid()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("validation error");
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String,Object>> handleAuth(AuthenticationException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid or expired token");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String,Object>> handleAccess(AccessDeniedException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "access denied");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> handleResponseStatus(ResponseStatusException ex) {
        org.springframework.http.HttpStatusCode sc = ex.getStatusCode();
        HttpStatus status = (sc instanceof HttpStatus) ? (HttpStatus) sc : HttpStatus.valueOf(sc.value());
        String code;
        if (status == HttpStatus.UNAUTHORIZED) code = "UNAUTHORIZED";
        else if (status == HttpStatus.FORBIDDEN) code = "FORBIDDEN";
        else if (status == HttpStatus.UNPROCESSABLE_ENTITY) code = "BAD_CREDENTIALS";
        else if (status == HttpStatus.TOO_MANY_REQUESTS) code = "RATE_LIMIT";
        else code = status.name();
        String msg = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return body(status, code, msg);
    }

//    @ExceptionHandler(Exception.class)
//    public ResponseEntity<Map<String,Object>> handleOther(Exception ex) {
//        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "server error");
//    }
}