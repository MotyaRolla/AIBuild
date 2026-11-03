package dev.aibuild.script;

/**
 * A script failure with enough context for the model to repair it in one call.
 *
 * @param kind    syntax | runtime | api | budget | lint
 * @param message the error message (from Rhino or from the stdlib argument checks)
 * @param line    1-based source line, or 0 when unknown
 * @param column  1-based column, or 0
 * @param excerpt the numbered source lines around {@code line}
 * @param stack   the JS stack (innermost first), possibly empty
 */
public record ScriptError(String kind, String message, int line, int column, String excerpt, String stack) {

    /** Compact report for a repair prompt. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind.toUpperCase(java.util.Locale.ROOT)).append(" ERROR");
        if (line > 0) sb.append(" at line ").append(line).append(column > 0 ? ":" + column : "");
        sb.append(": ").append(message).append('\n');
        if (excerpt != null && !excerpt.isBlank()) sb.append(excerpt);
        if (stack != null && !stack.isBlank()) sb.append("JS stack:\n").append(stack);
        return sb.toString();
    }

    /** Numbered lines {@code line-radius .. line+radius}, marking the failing one with "→". */
    static String excerpt(String source, int line, int radius) {
        if (source == null || line <= 0) return "";
        String[] lines = source.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(1, line - radius); i <= Math.min(lines.length, line + radius); i++) {
            String text = lines[i - 1];
            if (text.length() > 200) text = text.substring(0, 199) + "…";
            sb.append(i == line ? "→ " : "  ").append(String.format("%4d| ", i)).append(text).append('\n');
        }
        return sb.toString();
    }
}
