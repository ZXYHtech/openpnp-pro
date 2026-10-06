package org.openpnp.gui.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared, index-safe row reordering logic. The target is the row before which the selection is
 * inserted, or {@code source.size()} to append.
 */
public final class RowReorderSupport {
    private RowReorderSupport() {
    }

    public static <T> List<T> reorder(List<T> source, List<Integer> selectedRows,
            int targetRow) {
        if (source == null || selectedRows == null || selectedRows.isEmpty()) {
            return null;
        }
        List<Integer> rows = new ArrayList<>();
        for (Integer row : selectedRows) {
            if (row != null && row >= 0 && row < source.size() && !rows.contains(row)) {
                rows.add(row);
            }
        }
        Collections.sort(rows);
        if (rows.isEmpty() || (targetRow >= 0 && targetRow < source.size()
                && rows.contains(targetRow))) {
            return null;
        }
        T target = targetRow >= 0 && targetRow < source.size() ? source.get(targetRow) : null;
        List<T> moved = new ArrayList<>();
        List<T> result = new ArrayList<>(source);
        for (int row : rows) {
            moved.add(source.get(row));
        }
        result.removeAll(moved);
        int insertionIndex = target == null ? result.size() : result.indexOf(target);
        if (insertionIndex < 0) {
            insertionIndex = result.size();
        }
        result.addAll(insertionIndex, moved);
        return result.equals(source) ? null : result;
    }
}
