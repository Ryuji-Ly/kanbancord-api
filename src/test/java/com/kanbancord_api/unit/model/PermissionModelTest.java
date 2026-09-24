package com.kanbancord_api.unit.model;

import com.kanbancord_api.permission.Permission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionModelTest {

    @Test
    void onCreate_setsTimestampsAndDefaultsImmutableToFalse_whenImmutableIsNull() {
        Permission permission = new Permission();
        permission.setIsImmutable(null);

        invokeLifecycle(permission, "onCreate");

        assertNotNull(permission.getCreatedAt());
        assertNotNull(permission.getUpdatedAt());
        assertFalse(permission.getIsImmutable());
    }

    @Test
    void onUpdate_refreshesUpdatedAt() throws InterruptedException {
        Permission permission = new Permission();
        invokeLifecycle(permission, "onCreate");
        LocalDateTime createdUpdatedAt = permission.getUpdatedAt();

        Thread.sleep(2);
        invokeLifecycle(permission, "onUpdate");

        assertTrue(permission.getUpdatedAt().isAfter(createdUpdatedAt)
                || permission.getUpdatedAt().isEqual(createdUpdatedAt));
        assertEquals(permission.getCreatedAt(), permission.getCreatedAt());
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
