package com.kanbancord_api.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Ordering helpers for tasks within a column and columns within a board. */
public final class Positions {

    private Positions() {
    }

    /**
     * Numbers the items 1, 2, 3... in list order, touching only those whose position changes.
     *
     * @return whether any position changed
     */
    public static <T> boolean renumber(List<T> items, Function<T, BigDecimal> getter, BiConsumer<T, BigDecimal> setter) {
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

    /** Digits after the point a position may have; the database column keeps exactly this many. */
    public static final int SCALE = 10;

    /**
     * A position for an item going between {@code before} and {@code after} (either null at the start
     * or end of the list): the next whole number after the last, one before the first, or halfway
     * between two. Empty when the two are too close to fit another between them at {@link #SCALE},
     * when the list then needs renumbering.
     */
    public static Optional<BigDecimal> between(BigDecimal before, BigDecimal after) {
        if (before == null && after == null) {
            return Optional.of(BigDecimal.ONE);
        }
        if (after == null) {
            return Optional.of(before.setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE));
        }
        if (before == null) {
            return Optional.of(after.setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE));
        }
        BigDecimal middle = before.add(after).divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
        return middle.compareTo(before) > 0 && middle.compareTo(after) < 0 ? Optional.of(middle) : Optional.empty();
    }

    /** Inserts {@code item} at {@code index}, clamped to the end of the list. */
    public static <T> void insert(List<T> items, int index, T item) {
        items.add(Math.min(index, items.size()), item);
    }
}
