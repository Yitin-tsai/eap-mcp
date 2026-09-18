package com.eap.mcp.recovery;

import com.eap.mcp.recovery.api.RecoveryCaseListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecoveryControlControllerTest {

    @Test
    void missingOrWrongOperatorTokenShouldFailClosed() throws Exception {
        RecoveryControlService service = mock(RecoveryControlService.class);
        RecoveryControlController controller = new RecoveryControlController(
                service, mock(RecoveryAuditStore.class), "operator-secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/recovery/v1/cases"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/internal/recovery/v1/cases")
                        .header(RecoveryControlController.TOKEN_HEADER, "wrong"))
                .andExpect(status().isForbidden());
    }

    @Test
    void correctOperatorTokenShouldReachControlService() throws Exception {
        RecoveryControlService service = mock(RecoveryControlService.class);
        RecoveryControlController controller = new RecoveryControlController(
                service, mock(RecoveryAuditStore.class), "operator-secret");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(service.list(12)).thenReturn(new RecoveryCaseListResponse(Instant.now(), List.of(), List.of()));

        mvc.perform(get("/internal/recovery/v1/cases")
                        .param("limit", "12")
                        .header(RecoveryControlController.TOKEN_HEADER, "operator-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").isArray());
    }
}
