package com.kanbancord_api.priority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A priority level's name and colour. New levels are added at the bottom of the list. */
public class BoardPriorityRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 50, message = "Name must be at most 50 characters")
    private String name;

    /** A hex colour such as #dc2626; a neutral colour is used when absent. */
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "Color must be a hex colour such as #dc2626")
    private String color;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
