package com.kanbancord_api.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record BoardColumnRequest(
        @NotBlank(message = "Name is required") @Size(max = 50, message = "Name must not exceed 50 characters") String name,
        @NotNull(message = "Board ID is required") Long boardId,
        BigDecimal position,
        @Size(max = 7, message = "Color must be a valid hex color") String color,
        Integer wipLimit) {
}
