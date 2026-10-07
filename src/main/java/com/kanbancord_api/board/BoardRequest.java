package com.kanbancord_api.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BoardRequest(
        @NotBlank(message = "Name is required") @Size(max = 100, message = "Name must not exceed 100 characters") String name,
        @Size(max = 500, message = "Description must not exceed 500 characters") String description,
        @NotNull(message = "Server ID is required") Long serverId,
        Long createdBy,
        List<String> columnNames) {
}
