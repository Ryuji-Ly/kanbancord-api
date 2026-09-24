package com.kanbancord_api.unit.model;

import com.kanbancord_api.server.ServerMember;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMemberModelTest {

    @Test
    void onCreate_setsJoinedAtAndUpdatedAt() {
        ServerMember serverMember = new ServerMember();

        invokeLifecycle(serverMember, "onCreate");

        assertNotNull(serverMember.getJoinedAt());
        assertNotNull(serverMember.getUpdatedAt());
    }

    @Test
    void onUpdate_refreshesUpdatedAt() throws InterruptedException {
        ServerMember serverMember = new ServerMember();
        invokeLifecycle(serverMember, "onCreate");
        LocalDateTime originalUpdatedAt = serverMember.getUpdatedAt();

        Thread.sleep(2);
        invokeLifecycle(serverMember, "onUpdate");

        assertTrue(serverMember.getUpdatedAt().isAfter(originalUpdatedAt)
                || serverMember.getUpdatedAt().isEqual(originalUpdatedAt));
    }

    private void invokeLifecycle(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to invoke lifecycle method: " + methodName, ex);
        }
    }
}
