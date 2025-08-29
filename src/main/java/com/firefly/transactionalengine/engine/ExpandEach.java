package com.firefly.transactionalengine.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Explicit marker to indicate that a step expecting a single input should be expanded
 * into one step per item. The engine will clone the step definition using derived ids
 * and wire compensations identically so that compensation runs per item.
 *
 * Usage:
 *   StepInputs inputs = StepInputs.builder()
 *     .forStep(Steps::insertItems, ExpandEach.of(items))
 *     .build();
 */
public final class ExpandEach {
    private final List<?> items;
    private final Function<Object, String> idSuffixFn; // optional

    private ExpandEach(List<?> items, Function<Object, String> idSuffixFn) {
        this.items = items;
        this.idSuffixFn = idSuffixFn;
    }

    /** Expand using default id scheme stepId#idx. */
    public static ExpandEach of(Iterable<?> items) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>();
        for (Object it : items) list.add(it);
        return new ExpandEach(list, null);
    }

    /** Expand using a custom suffix derived from each item (e.g., by id). */
    public static ExpandEach of(Iterable<?> items, Function<Object, String> idSuffixFn) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>();
        for (Object it : items) list.add(it);
        return new ExpandEach(list, Objects.requireNonNull(idSuffixFn, "idSuffixFn"));
    }

    /** Convenience overload for arrays. */
    public static ExpandEach of(Object[] items) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>(items.length);
        for (Object it : items) list.add(it);
        return new ExpandEach(list, null);
    }

    public List<?> items() { return items; }
    public Optional<Function<Object, String>> idSuffixFn() { return Optional.ofNullable(idSuffixFn); }
}
