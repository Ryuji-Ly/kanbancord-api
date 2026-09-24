package com.kanbancord_api.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Ordering helpers for tasks within a column and columns within a board. */
final class Positions {

    private Positions() {
    }

    /**
     * Numbers the items 1, 2, 3... in list order, touching only those whose position changes.
     *
     * @return whether any position changed
     */
    static <T> boolean renumber(List<T> items, Function<T, BigDecimal> getter, BiConsumer<T, BigDecimal> setter) {
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            BigDecimal position = BigDecimal.valueOf(i + 1L);
            BigDecimal current = getter.apply(items.get(i));
            if (current == null || current.compareTo(position) != 0) {
                setter.accept(items.get(i), position);
                changed = true;
            }
        }
        return changed;
    }

    /** Inserts {@code item} at {@code index}, clamped to the end of the list. */
    static <T> void insert(List<T> items, int index, T item) {
        items.add(Math.min(index, items.size()), item);
    }
}
