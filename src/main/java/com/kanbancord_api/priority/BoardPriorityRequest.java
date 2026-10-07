package com.kanbancord_api.priority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A priority level's name and colour. New levels are added at the bottom of the list. */
public record BoardPriorityRequest(
        @NotBlank(message = "Name is required") @Size(max = 50, message = "Name must be at most 50 characters") String name,
        /** A hex colour such as #dc2626; a neutral colour is used when absent. */ @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a hex colour such as #dc2626") String color) {
}
