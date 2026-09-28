package com.team1.settlement.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 오류 응답을 다른 서비스와 같은 {@code {success:false, data:{code}}} 형식으로 맞춘다.
 * 프론트는 401 + {@code data.code == UNAUTHENTICATED} 일 때만 만료 Token 을 지우고 로그인 화면으로 보낸다 -
 * 형식이 다르면 관리자 Token 이 만료돼도 정산 화면에 알 수 없는 오류만 뜬다.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        Map<String, Object> body = Map.of(
                "success", false,
                "data", Map.of("code", codeFor(status)),
                "message", e.getReason() == null ? codeFor(status) : e.getReason());
        return ResponseEntity.status(status).body(body);
    }

    private static String codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            return "UNAUTHENTICATED";
        }
        if (status.value() == HttpStatus.FORBIDDEN.value()) {
            return "FORBIDDEN";
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "HTTP_" + status.value() : known.name();
    }
}
