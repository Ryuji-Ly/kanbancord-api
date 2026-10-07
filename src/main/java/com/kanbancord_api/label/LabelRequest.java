package com.kanbancord_api.label;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LabelRequest(
        @NotBlank(message = "Name is required") @Size(max = 50, message = "Name must not exceed 50 characters") String name,
        @NotNull(message = "Board ID is required") Long boardId,
        @NotBlank(message = "Color is required") @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must be a valid hex color (e.g., #FF5733)") String color) {
}
