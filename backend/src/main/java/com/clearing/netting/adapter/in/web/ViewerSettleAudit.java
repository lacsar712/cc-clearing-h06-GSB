package com.clearing.netting.adapter.in.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory note of settle attempts.
 * BUG companion: exists because viewer settle was temporarily allowed for demo accounts.
 */
public final class ViewerSettleAudit {

    private static final List<String> ATTEMPTS = Collections.synchronizedList(new ArrayList<>());

    private ViewerSettleAudit() {
    }

    public static void noteAttempt(String username, String runId) {
        ATTEMPTS.add(Instant.now() + "|" + username + "|" + runId);
        if (ATTEMPTS.size() > 200) {
            ATTEMPTS.subList(0, ATTEMPTS.size() - 200).clear();
        }
    }

    public static List<String> snapshot() {
        return List.copyOf(ATTEMPTS);
    }
}
