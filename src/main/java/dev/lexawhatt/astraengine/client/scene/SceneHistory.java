package dev.lexawhatt.astraengine.client.scene;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Client-thread-owned document history, retaining at most 64 prior edits. This type is not thread-safe.
 * A commit after undo discards redo. Cross-dimension documents are allowed for explicit preset loading;
 * the caller owns session/dimension applicability. No-op edits do not consume history.
 */
public final class SceneHistory {
    public static final int MAX_HISTORY = 64;

    private final Deque<SceneDocument> undo = new ArrayDeque<>();
    private final Deque<SceneDocument> redo = new ArrayDeque<>();
    private SceneDocument current;

    /** Starts a history with one non-null document and no pending undo or redo. */
    public SceneHistory(SceneDocument initial) {
        current = requireDocument(initial);
    }

    /** Returns the current immutable scene, safe to hand to a worker as a snapshot. */
    public SceneDocument current() {
        return current;
    }

    /** Commits a distinct document; null is rejected and equal documents leave undo/redo untouched. */
    public void commit(SceneDocument document) {
        requireDocument(document);
        if (current.equals(document)) {
            return;
        }
        undo.addLast(current);
        if (undo.size() > MAX_HISTORY) {
            undo.removeFirst();
        }
        current = document;
        redo.clear();
    }

    /** Restores the preceding document, or returns the current document when history is exhausted. */
    public SceneDocument undo() {
        if (!undo.isEmpty()) {
            redo.addLast(current);
            current = undo.removeLast();
        }
        return current;
    }

    /** Reapplies the next document, or returns the current document when no redo remains. */
    public SceneDocument redo() {
        if (!redo.isEmpty()) {
            undo.addLast(current);
            current = redo.removeLast();
        }
        return current;
    }

    /** Whether an earlier document is available. */
    public boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Whether an undone document is available. */
    public boolean canRedo() {
        return !redo.isEmpty();
    }

    private static SceneDocument requireDocument(SceneDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Scene document cannot be null");
        }
        return document;
    }
}
