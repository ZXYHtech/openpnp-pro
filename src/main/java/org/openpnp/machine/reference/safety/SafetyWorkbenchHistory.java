/*
 * Copyright (C) 2026 OpenPnP Pro contributors
 */
package org.openpnp.machine.reference.safety;

import java.util.ArrayDeque;
import java.util.Deque;

import com.google.gson.Gson;

/** Bounded model snapshots used by the workbench undo and redo controls. */
public final class SafetyWorkbenchHistory {
    private static final int MAX_HISTORY = 80;
    private static final Gson GSON = new Gson();

    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private String current;

    public SafetyWorkbenchHistory(SafetyWorkbenchModel model) {
        current = serialize(model);
    }

    public boolean capture(SafetyWorkbenchModel model) {
        String next = serialize(model);
        if (next.equals(current)) {
            return false;
        }
        undo.addLast(current);
        trim(undo);
        redo.clear();
        current = next;
        return true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean undo(SafetyWorkbenchModel model) {
        if (undo.isEmpty()) {
            return false;
        }
        redo.addLast(serialize(model));
        trim(redo);
        restore(model, undo.removeLast());
        current = serialize(model);
        return true;
    }

    public boolean redo(SafetyWorkbenchModel model) {
        if (redo.isEmpty()) {
            return false;
        }
        undo.addLast(serialize(model));
        trim(undo);
        restore(model, redo.removeLast());
        current = serialize(model);
        return true;
    }

    private static void restore(SafetyWorkbenchModel model, String json) {
        SafetyWorkbenchModel restored = GSON.fromJson(json, SafetyWorkbenchModel.class);
        model.restoreFrom(restored);
    }

    private static String serialize(SafetyWorkbenchModel model) {
        if (model == null) {
            throw new IllegalArgumentException("工作台模型不能为空。");
        }
        return GSON.toJson(model);
    }

    private static void trim(Deque<String> history) {
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }
}
