package com.kanbancord_api.label;

public record LabelResponse(
        Long labelId,
        Long boardId,
        String name,
        String color) {

    public static LabelResponse from(Label label) {
        return new LabelResponse(
                label.getLabelId(),
                label.getBoard().getBoardId(),
                label.getName(),
                label.getColor());
    }
}
