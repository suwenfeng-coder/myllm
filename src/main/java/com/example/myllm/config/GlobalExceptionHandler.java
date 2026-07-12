package com.example.myllm.config;

import com.example.myllm.harness.domain.HarnessDomainException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException ex) {
        log.warn("请求失败 status={} reason={}", ex.getStatusCode(), ex.getReason(), ex);
        return ResponseEntity.status(ex.getStatusCode()).body(ex.getReason());
    }

    /**
     * 静态资源不存在属于正常的 404 请求，不记录为服务器错误或输出异常堆栈。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("静态资源不存在 path={}", ex.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    /** Harness 领域错误返回稳定错误码，避免 API 客户端依赖异常文本。 */
    @ExceptionHandler(HarnessDomainException.class)
    public ResponseEntity<Map<String, String>> handleHarnessDomain(HarnessDomainException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_STATE_TRANSITION, LEASE_MISMATCH, LEASE_EXPIRED -> HttpStatus.CONFLICT;
            case TOOL_NOT_ALLOWED, APPROVAL_REQUIRED, APPROVAL_EXPIRED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        log.warn("Harness 请求失败 code={} message={}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(status).body(Map.of(
                "code", ex.getErrorCode().name(),
                "message", ex.getMessage() == null ? "Harness 请求失败" : ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("服务器内部错误: " + ex.getMessage());
    }
}
