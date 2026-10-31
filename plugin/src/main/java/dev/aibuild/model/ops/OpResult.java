package dev.aibuild.model.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Accumulated outcome of one batch of DSL operations. Collects counters, errors, warnings and
 * informational notes and renders them as a compact text block that can be fed back to an LLM.
 *
 * <p>Not thread-safe: a single instance is filled by one {@link OpExecutor#apply} call on one thread.
 * Counters are plain public fields for cheap access by callers; the lists are live and mutable.</p>
 *
 * <p>Error messages are expected in the form {@code op[3] fill: ...} (zero based op index, canonical
 * op name, human readable reason). Warnings do not fail an op; info lines describe generated content
 * (for example a roof summary).</p>
 */
public final class OpResult {
    /** Number of entries that were offered to the executor. */
    public int opsTotal;
    /** Number of entries that completed without an error. */
    public int opsOk;
    /** Number of entries that were rejected (execution continued with the next entry). */
    public int opsFailed;
    /** Number of model cells whose stored value actually changed. */
    public int cellsChanged;

    /** One message per failed op (plus batch level failures). */
    public final List<String> errors = new ArrayList<>();
    /** Non-fatal remarks (clamped values, overwritten cells, ...). */
    public final List<String> warnings = new ArrayList<>();
    /** Informational lines for the model description (e.g. {@code roof: style=gable top_y=12}). */
    public final List<String> info = new ArrayList<>();
    public final List<Doorway> doorways = new ArrayList<>();

    private static final int MAX_ERROR_LINES = 40;
    private static final int MAX_WARNING_LINES = 20;
    private static final int MAX_INFO_LINES = 20;
    private static final int MAX_LINE_CHARS = 400;

    /** Creates an empty result. */
    public OpResult() {
    }

    public int opsTotal() {
        return opsTotal;
    }

    public int opsOk() {
        return opsOk;
    }

    public int opsFailed() {
        return opsFailed;
    }

    public int cellsChanged() {
        return cellsChanged;
    }

    /** True when at least one op failed. */
    public boolean hasErrors() {
        return !errors.isEmpty() || opsFailed > 0;
    }

    /** True when no op failed. */
    public boolean isOk() {
        return !hasErrors();
    }

    /** Appends an error message verbatim. */
    public void error(String message) {
        if (message != null) errors.add(message);
    }

    /** Appends a warning message verbatim. */
    public void warning(String message) {
        if (message != null) warnings.add(message);
    }

    /** Appends an info line verbatim. */
    public void info(String message) {
        if (message != null) info.add(message);
    }

    /**
     * Merges another result into this one: counters are summed, message lists appended in order.
     *
     * @return {@code this}, for chaining
     */
    public OpResult merge(OpResult other) {
        if (other == null || other == this) return this;
        opsTotal += other.opsTotal;
        opsOk += other.opsOk;
        opsFailed += other.opsFailed;
        cellsChanged += other.cellsChanged;
        errors.addAll(other.errors);
        warnings.addAll(other.warnings);
        info.addAll(other.info);
        doorways.addAll(other.doorways);
        return this;
    }

    /**
     * One compact multi-line report aimed at LLM feedback: a header with the counters, then up to
     * 40 error lines, 20 warning lines and 20 info lines. Overflow is truncated with
     * {@code ... N more}. Individual messages are shortened to 400 characters.
     */
    public String summary() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("ops: ").append(opsOk).append('/').append(opsTotal).append(" ok");
        if (opsFailed > 0) sb.append(", ").append(opsFailed).append(" failed");
        sb.append("; cells changed: ").append(cellsChanged);
        appendSection(sb, "errors", errors, MAX_ERROR_LINES);
        appendSection(sb, "warnings", warnings, MAX_WARNING_LINES);
        appendSection(sb, "info", info, MAX_INFO_LINES);
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> items, int max) {
        if (items.isEmpty()) return;
        sb.append('\n').append(title).append(" (").append(items.size()).append("):");
        int n = Math.min(items.size(), max);
        for (int i = 0; i < n; i++) {
            sb.append("\n- ").append(shorten(items.get(i)));
        }
        if (items.size() > n) {
            sb.append("\n- ... ").append(items.size() - n).append(" more");
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String one = s.replace('\r', ' ').replace('\n', ' ').trim();
        if (one.length() <= MAX_LINE_CHARS) return one;
        return one.substring(0, MAX_LINE_CHARS - 3) + "...";
    }

    @Override
    public String toString() {
        return summary();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpResult r)) return false;
        return opsTotal == r.opsTotal && opsOk == r.opsOk && opsFailed == r.opsFailed
                && cellsChanged == r.cellsChanged && errors.equals(r.errors)
                && warnings.equals(r.warnings) && info.equals(r.info);
    }

    @Override
    public int hashCode() {
        return Objects.hash(opsTotal, opsOk, opsFailed, cellsChanged, errors, warnings, info);
    }
}
