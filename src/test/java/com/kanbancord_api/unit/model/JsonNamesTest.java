package com.kanbancord_api.unit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kanbancord_api.board.BoardResponse;
import com.kanbancord_api.permission.PermissionDecisionResponse;
import com.kanbancord_api.permission.PermissionRequest;
import com.kanbancord_api.permission.PermissionResponse;
import com.kanbancord_api.task.TaskResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The website and the bot read these names; records must keep them exactly as the classes they
 * replaced did: "isArchived" (not "archived"), snowflake ids as strings, and so on.
 */
class JsonNamesTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void responsesKeepTheirJsonNames() throws Exception {
        JsonNode board = mapper.valueToTree(new BoardResponse(1L, 900000000000000001L, "Sprint", null, true,
                100000000000000001L, null, null));
        assertTrue(board.get("isArchived").asBoolean());
        assertEquals("900000000000000001", board.get("serverId").asText());
        assertTrue(board.get("serverId").isTextual(), "snowflakes stay strings");
        assertEquals("100000000000000001", board.get("createdBy").asText());

        JsonNode rule = mapper.valueToTree(new PermissionResponse(5L, "SERVER", 900000000000000001L, "ROLE",
                800000000000000001L, 3, "EDIT_TASK", "ALLOW", 100, true, null, null));
        assertTrue(rule.get("isImmutable").asBoolean());
        assertTrue(rule.get("subjectId").isTextual());
        assertEquals("EDIT_TASK", rule.get("kanbanPermissionKey").asText());

        JsonNode decision = mapper.valueToTree(new PermissionDecisionResponse(true, "ROLE", "SERVER", 1L, 2L));
        assertTrue(decision.get("allowed").asBoolean());
        assertEquals("ROLE", decision.get("sourceTier").asText());

        JsonNode task = mapper.valueToTree(new TaskResponse(100L, 1L, 10L, "Fix", null, new BigDecimal("1.00"), null,
                null, false, Map.of(), 100000000000000001L, null, null, null));
        assertTrue(task.has("isArchived") && !task.has("archived"));
        assertTrue(task.get("createdBy").isTextual());
    }

    @Test
    void requestsReadTheSameJson() throws Exception {
        PermissionRequest request = mapper.readValue("""
                {"scopeType":"BOARD","scopeId":100,"subjectType":"ROLE","subjectId":20,"kanbanPermissionId":11,
                 "state":"ALLOW","priority":100,"isImmutable":false}
                """, PermissionRequest.class);
        assertEquals("BOARD", request.scopeType());
        assertEquals(11, request.kanbanPermissionId());
        assertEquals(Boolean.FALSE, request.isImmutable());
    }
}
