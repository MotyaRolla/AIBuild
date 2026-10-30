package dev.aibuild.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One objective finding produced by {@link StructureAnalyzer}.
 *
 * <p>Instances are immutable value objects and safe to share. {@code samples} holds at most
 * {@link #MAX_SAMPLES} example positions ({@code [x,y,z]} arrays) that illustrate the issue; they are
 * copied on construction and on access, so callers cannot mutate the issue.</p>
 *
 * <p>{@link #toLine()} renders the single-line form used in LLM prompts, for example:</p>
 * <pre>
 * MAJOR floating_blocks: 12 blocks not connected to the ground (component of 12 at x3..5 y7..8 z2..4) e.g. (3,7,2) (4,7,2)
 * </pre>
 */
public record Issue(Severity severity, String code, String message, List<int[]> samples) {

    /** Maximum number of sample positions kept per issue. */
    public static final int MAX_SAMPLES = 8;

    /** Compact canonical constructor: defends against nulls and oversized/invalid sample lists. */
    public Issue {
        Objects.requireNonNull(severity, "severity");
        if (code == null || code.isBlank()) code = "issue";
        code = code.trim().toLowerCase(Locale.ROOT);
        message = message == null ? "" : message.trim();
        samples = copySamples(samples);
    }

    /** Convenience factory for an issue without samples. */
    public static Issue of(Severity severity, String code, String message) {
        return new Issue(severity, code, message, List.of());
    }

    /** Convenience factory for an issue with sample positions. */
    public static Issue of(Severity severity, String code, String message, List<int[]> samples) {
        return new Issue(severity, code, message, samples);
    }

    private static List<int[]> copySamples(List<int[]> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<int[]> out = new ArrayList<>(Math.min(in.size(), MAX_SAMPLES));
        for (int[] p : in) {
            if (p == null || p.length < 3) continue;
            out.add(new int[]{p[0], p[1], p[2]});
            if (out.size() >= MAX_SAMPLES) break;
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    /** Defensive copy of the sample positions. */
    @Override
    public List<int[]> samples() {
        if (samples.isEmpty()) return samples;
        List<int[]> out = new ArrayList<>(samples.size());
        for (int[] p : samples) out.add(new int[]{p[0], p[1], p[2]});
        return Collections.unmodifiableList(out);
    }

    /** Number of samples stored (0..{@link #MAX_SAMPLES}). */
    public int sampleCount() {
        return samples.size();
    }

    /** Single-line rendering, at most 8 samples, e.g. {@code MINOR flat_facade_west: ... e.g. (0,2,1)}. */
    public String toLine() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(severity.name()).append(' ').append(code);
        if (!message.isEmpty()) sb.append(": ").append(message);
        if (!samples.isEmpty()) {
            sb.append(" e.g.");
            for (int[] p : samples) sb.append(" (").append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(')');
        }
        return sb.toString();
    }

    /**
     * Machine-readable single-line form for JSON-ish payloads:
     * {@code {"severity":"MAJOR","code":"...","message":"...","samples":[[x,y,z],...]}}
     * (quotes and control characters in the message are escaped).
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"severity\":\"").append(severity.name()).append("\",\"code\":\"").append(escape(code))
                .append("\",\"message\":\"").append(escape(message)).append("\",\"samples\":[");
        for (int i = 0; i < samples.size(); i++) {
            int[] p = samples.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(']');
        }
        return sb.append("]}").toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toLine();
    }
}
