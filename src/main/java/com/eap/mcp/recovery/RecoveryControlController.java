package com.eap.mcp.recovery;

import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.mcp.recovery.api.ManagedRecoveryCaseDetail;
import com.eap.mcp.recovery.api.RecoveryActionAuditView;
import com.eap.mcp.recovery.api.RecoveryCaseListResponse;
import com.eap.mcp.recovery.api.RecoveryControlActionRequest;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/internal/recovery/v1")
@ConditionalOnProperty(name = "eap.recovery-control.enabled", havingValue = "true")
public class RecoveryControlController {

    public static final String TOKEN_HEADER = "X-EAP-Recovery-Operator-Token";
    public static final String OPERATOR_HEADER = "X-EAP-Operator";

    private final RecoveryControlService service;
    private final RecoveryAuditStore auditStore;
    private final byte[] expectedToken;

    public RecoveryControlController(
            RecoveryControlService service,
            RecoveryAuditStore auditStore,
            @Value("${eap.recovery-control.operator-token}") String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalArgumentException("eap.recovery-control.operator-token must be configured when enabled");
        }
        this.service = service;
        this.auditStore = auditStore;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/cases")
    public RecoveryCaseListResponse list(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "50") int limit) {
        authorize(token);
        return service.list(limit);
    }

    @GetMapping("/cases/{caseId}")
    public ManagedRecoveryCaseDetail detail(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String caseId) {
        authorize(token);
        return service.detail(caseId);
    }

    @PostMapping("/cases/{caseId}/dry-run")
    public RecoveryDryRunResult dryRun(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String caseId,
            @RequestBody RecoveryDryRunRequest request) {
        authorize(token);
        return service.dryRun(caseId, request);
    }

    @PostMapping("/cases/{caseId}/actions")
    public RecoveryExecuteResult execute(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = OPERATOR_HEADER, required = false) String operator,
            @PathVariable String caseId,
            @RequestBody RecoveryControlActionRequest request) {
        authorize(token);
        return service.execute(caseId, request, operator);
    }

    @GetMapping("/actions/{actionId}")
    public RecoveryActionAuditView action(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable UUID actionId) {
        authorize(token);
        return auditStore.action(actionId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(RecoveryRateLimitExceededException.class)
    ResponseEntity<Map<String, String>> rateLimited(RecoveryRateLimitExceededException failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(FeignException.NotFound.class)
    ResponseEntity<Map<String, String>> sourceNotFound(FeignException.NotFound failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Recovery source case not found"));
    }

    @ExceptionHandler(FeignException.class)
    ResponseEntity<Map<String, String>> sourceUnavailable(FeignException failure) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Recovery source request failed", "status", Integer.toString(failure.status())));
    }

    @ExceptionHandler(RecoveryOperatorForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
    }

    private void authorize(String token) {
        byte[] provided = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, provided)) {
            throw new RecoveryOperatorForbiddenException();
        }
    }

    private static final class RecoveryOperatorForbiddenException extends RuntimeException {
    }
}
