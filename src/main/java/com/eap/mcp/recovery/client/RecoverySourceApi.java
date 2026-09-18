package com.eap.mcp.recovery.client;

import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecuteResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface RecoverySourceApi {

    String TOKEN_HEADER = "X-EAP-Recovery-Source-Token";

    @GetMapping("/internal/recovery/v1/cases")
    List<RecoveryCaseSummary> list(
            @RequestHeader(TOKEN_HEADER) String token,
            @RequestParam("limit") int limit);

    @GetMapping("/internal/recovery/v1/cases/{caseId}")
    RecoveryCaseDetail detail(
            @RequestHeader(TOKEN_HEADER) String token,
            @PathVariable("caseId") String caseId);

    @PostMapping("/internal/recovery/v1/cases/{caseId}/dry-run")
    RecoveryDryRunResult dryRun(
            @RequestHeader(TOKEN_HEADER) String token,
            @PathVariable("caseId") String caseId,
            @RequestBody RecoveryDryRunRequest request);

    @PostMapping("/internal/recovery/v1/cases/{caseId}/execute")
    RecoveryExecuteResult execute(
            @RequestHeader(TOKEN_HEADER) String token,
            @PathVariable("caseId") String caseId,
            @RequestBody RecoveryExecuteRequest request);
}
