package dev.aibuild.analysis;

import java.util.Locale;

/**
 * Severity of an {@link Issue}, ordered from most to least important.
 *
 * <p>{@link #CRITICAL} and {@link #MAJOR} make a report "blocking"
 * ({@link AnalysisReport#hasBlocking()}): the build is broken and must be fixed before it is placed.
 * {@link #MINOR} and {@link #INFO} are quality hints for the reviewing LLM.</p>
 */
public enum Severity {
    /** Broken build: empty model, unusable structure, it cannot be approved. */
    CRITICAL(0),
    /** An objective defect the LLM must fix (floating blocks, dark rooms, blocked doors, ...). */
    MAJOR(1),
    /** Rough edges worth fixing if cheap (incomplete door, monotonous facade, ...). */
    MINOR(2),
    /** Neutral observation, never a defect. */
    INFO(3);

    /** Sort rank: lower is more severe. */
    public final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    /** Lower-case id used in machine-readable output, e.g. {@code "major"}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses {@code "MAJOR"}, {@code "major"}, {@code "warn"} etc. Returns null when unknown. */
    public static Severity parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "crit", "fatal", "error" -> CRITICAL;
            case "major", "high", "warn", "warning" -> MAJOR;
            case "minor", "low" -> MINOR;
            case "info", "note", "ok" -> INFO;
            default -> null;
        };
    }
}
