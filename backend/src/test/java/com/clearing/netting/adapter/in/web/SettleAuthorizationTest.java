package com.clearing.netting.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interface-level regression: settle is operator-only while viewers retain read access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettleAuthorizationTest {

    private static final String SETTLE_DATE = LocalDate.of(2026, 9, 10).toString();
    private static final String CURRENCY = "USD";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void viewerSettleIsForbiddenButOperatorSettleSucceedsAndViewerCanStillReadPositions() throws Exception {
        String operatorToken = login("operator", "op123456");
        String viewerToken = login("viewer", "view123456");

        String memberA = createMember(operatorToken, "Auth Reg A");
        String memberB = createMember(operatorToken, "Auth Reg B");
        createObligation(operatorToken, memberA, memberB, "100000.00000000");
        createObligation(operatorToken, memberB, memberA, "100000.00000000");

        String runId = executeRun(operatorToken);

        // Viewer direct-call settle must be rejected with 403/FORBIDDEN.
        ResponseEntity<String> viewerSettle = exchange(
                "/api/netting-runs/" + runId + "/settle", HttpMethod.POST, viewerToken, null);
        assertEquals(HttpStatus.FORBIDDEN, viewerSettle.getStatusCode());
        JsonNode viewerSettleBody = mapper.readTree(viewerSettle.getBody());
        assertEquals("FORBIDDEN", viewerSettleBody.path("code").asText());

        // Viewer must still be able to browse net positions (read-only access intact).
        ResponseEntity<String> viewerPositions = exchange(
                "/api/netting-runs/" + runId + "/positions", HttpMethod.GET, viewerToken, null);
        assertEquals(HttpStatus.OK, viewerPositions.getStatusCode());
        JsonNode positions = mapper.readTree(viewerPositions.getBody());
        assertTrue(positions.isArray() && positions.size() > 0, "viewer should see net positions");

        // Operator settle on a COMPLETED run still succeeds.
        ResponseEntity<String> operatorSettle = exchange(
                "/api/netting-runs/" + runId + "/settle", HttpMethod.POST, operatorToken, null);
        assertEquals(HttpStatus.OK, operatorSettle.getStatusCode());
        JsonNode settledRun = mapper.readTree(operatorSettle.getBody());
        assertEquals("COMPLETED", settledRun.path("status").asText());

        // Obligations linked to the run must actually be SETTLED.
        ResponseEntity<String> settled = exchange(
                "/api/obligations?currency=" + CURRENCY + "&settleDate=" + SETTLE_DATE + "&status=SETTLED",
                HttpMethod.GET, operatorToken, null);
        assertEquals(HttpStatus.OK, settled.getStatusCode());
        JsonNode settledList = mapper.readTree(settled.getBody());
        assertTrue(settledList.isArray() && settledList.size() >= 2);
        for (JsonNode o : settledList) {
            assertEquals(runId, o.path("nettingRunId").asText());
            assertEquals("SETTLED", o.path("status").asText());
        }
    }

    private String login(String username, String password) {
        try {
            ResponseEntity<String> resp = rest.postForEntity(
                    base("/api/auth/login"),
                    Map.of("username", username, "password", password),
                    String.class);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            JsonNode body = mapper.readTree(resp.getBody());
            String token = body.path("token").asText();
            assertNotNull(token);
            return token;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String createMember(String token, String name) throws Exception {
        ResponseEntity<String> resp = exchange("/api/members", HttpMethod.POST, token, Map.of("name", name));
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return mapper.readTree(resp.getBody()).path("memberId").asText();
    }

    private void createObligation(String token, String payer, String payee, String amount) {
        Map<String, Object> body = Map.of(
                "payerMemberId", payer,
                "payeeMemberId", payee,
                "currency", CURRENCY,
                "amount", amount,
                "tradeDate", SETTLE_DATE,
                "settleDate", SETTLE_DATE);
        ResponseEntity<String> resp = exchange("/api/obligations", HttpMethod.POST, token, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    private String executeRun(String token) throws Exception {
        Map<String, Object> body = Map.of("settleDate", SETTLE_DATE, "currency", CURRENCY);
        ResponseEntity<String> resp = exchange("/api/netting-runs", HttpMethod.POST, token, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode json = mapper.readTree(resp.getBody());
        assertEquals("COMPLETED", json.path("run").path("status").asText());
        return json.path("run").path("runId").asText();
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(base(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private String base(String path) {
        return "http://localhost:" + port + path;
    }
}
