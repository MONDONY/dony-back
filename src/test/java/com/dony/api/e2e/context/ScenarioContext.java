package com.dony.api.e2e.context;

import io.cucumber.spring.ScenarioScope;
import io.restassured.response.Response;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Scenario-scoped bean: holds state shared between step definitions within a single scenario.
 * A new instance is created for each Cucumber scenario.
 */
@Component
@ScenarioScope
public class ScenarioContext {

    private String currentUid;
    private String currentRoles;
    private Response lastResponse;

    // Named aliases: "traveler" → UUID, "announcement-1" → UUID, etc.
    private final Map<String, UUID> savedIds = new HashMap<>();
    private final Map<String, String> savedStrings = new HashMap<>();

    // ── Identity ─────────────────────────────────────────────────────────────

    public void setCurrentUser(String uid, String roles) {
        this.currentUid = uid;
        this.currentRoles = roles;
    }

    public String getCurrentUid() { return currentUid; }
    public String getCurrentRoles() { return currentRoles; }

    // ── HTTP response ─────────────────────────────────────────────────────────

    public void setLastResponse(Response response) { this.lastResponse = response; }
    public Response getLastResponse() { return lastResponse; }

    // ── Named IDs ─────────────────────────────────────────────────────────────

    public void saveId(String alias, UUID id) { savedIds.put(alias, id); }
    public UUID getId(String alias) {
        UUID id = savedIds.get(alias);
        if (id == null) throw new IllegalStateException("No saved ID for alias: " + alias);
        return id;
    }

    public void saveString(String key, String value) { savedStrings.put(key, value); }
    public String getString(String key) { return savedStrings.get(key); }
}
