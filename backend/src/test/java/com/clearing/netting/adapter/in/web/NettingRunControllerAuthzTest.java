package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.JwtAuthFilter;
import com.clearing.netting.adapter.out.security.JwtTokenService;
import com.clearing.netting.application.NettingApplicationService;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization regression for the settle endpoint:
 * viewers (read-only) must be rejected with 403, operators may settle COMPLETED runs,
 * and viewers must still be able to browse net positions.
 */
class NettingRunControllerAuthzTest {

    private static final String RUN_ID = "run-authz-1";

    private MockMvc mockMvc;
    private NettingApplicationService nettingService;
    private String operatorToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        nettingService = mock(NettingApplicationService.class);
        JwtTokenService tokenService = new JwtTokenService(
                "clearing-netting-demo-secret-key-32bytes!!", 86_400_000L);
        operatorToken = tokenService.issueToken("operator", "OPERATOR");
        viewerToken = tokenService.issueToken("viewer", "VIEWER");

        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(tokenService, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new NettingRunController(nettingService))
                .addFilters(jwtAuthFilter)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void viewerSettleIsForbiddenAndNeverReachesService() throws Exception {
        mockMvc.perform(post("/api/netting-runs/{id}/settle", RUN_ID)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(nettingService, never()).settle(any());
    }

    @Test
    void unauthenticatedSettleIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/netting-runs/{id}/settle", RUN_ID))
                .andExpect(status().isUnauthorized());

        verify(nettingService, never()).settle(any());
    }

    @Test
    void operatorCanSettleCompletedRun() throws Exception {
        NettingRun completedRun = new NettingRun(
                RUN_ID,
                LocalDate.of(2026, 9, 10),
                "USD",
                NettingRunStatus.COMPLETED,
                Instant.parse("2026-09-10T08:00:00Z"),
                null);
        when(nettingService.settle(RUN_ID)).thenReturn(completedRun);

        mockMvc.perform(post("/api/netting-runs/{id}/settle", RUN_ID)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(nettingService).settle(RUN_ID);
    }

    @Test
    void viewerCanStillReadNetPositions() throws Exception {
        List<NetPosition> positions = List.of(
                NetPosition.of(RUN_ID, "A", "USD", new BigDecimal("-60.00")),
                NetPosition.of(RUN_ID, "B", "USD", new BigDecimal("60.00")));
        when(nettingService.getPositions(eq(RUN_ID))).thenReturn(positions);

        mockMvc.perform(get("/api/netting-runs/{id}/positions", RUN_ID)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].runId").value(RUN_ID));
    }
}
