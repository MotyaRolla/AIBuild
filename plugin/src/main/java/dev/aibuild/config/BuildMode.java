package dev.aibuild.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A thinking-mode preset: how much the model reasons in each pipeline role, how many self-review rounds run and
 * how large the output budget is. Presets: {@code fast} (no thinking), {@code light} (small thinking),
 * {@code standard} (default) and {@code extra} (extra thinking). Every field can be overridden in config.yml
 * under {@code modes.<id>}.
 *
 * <p>Reasoning-effort values understood by the Command Code gateway for DeepSeek V4.x:
 * {@code off | low | medium | high | xhigh | max}.</p>
 */
public final class BuildMode {
    public static final String FAST = "fast", LIGHT = "light", STANDARD = "standard", EXTRA = "extra";

    public final String id;
    public final String title;
    public final String eta;
    public final String effortPlan, effortBuild, effortReview, effortReviewFollowup, effortEdit;
    public final int verifyRounds, editVerifyRounds, maxTokens;
    public final boolean parallelReview;

    public BuildMode(String id, String title, String eta, String plan, String build, String review, String followup,
                     String edit, int verifyRounds, int editVerifyRounds, int maxTokens, boolean parallelReview) {
        this.id = id;
        this.title = title;
        this.eta = eta;
        this.effortPlan = plan;
        this.effortBuild = build;
        this.effortReview = review;
        this.effortReviewFollowup = followup;
        this.effortEdit = edit;
        this.verifyRounds = verifyRounds;
        this.editVerifyRounds = editVerifyRounds;
        this.maxTokens = maxTokens;
        this.parallelReview = parallelReview;
    }

    /** Built-in presets (used when config.yml has no {@code modes} section or misses fields). */
    public static Map<String, BuildMode> defaults() {
        Map<String, BuildMode> m = new LinkedHashMap<>();
        m.put(FAST, new BuildMode(FAST, "Без думания", "~3–5 мин",
                "off", "off", "off", "off", "off", 3, 1, 32000, true));
        m.put(LIGHT, new BuildMode(LIGHT, "Лёгкое думание", "~8–11 мин",
                "off", "low", "off", "off", "low", 2, 1, 60000, true));
        m.put(STANDARD, new BuildMode(STANDARD, "Стандарт", "время зависит от сложности",
                "off", "low", "low", "low", "low", 3, 1, 60000, false));
        m.put(EXTRA, new BuildMode(EXTRA, "Экстра-думание", "время зависит от сложности",
                "low", "high", "high", "low", "high", 3, 2, 200000, true));
        return m;
    }

    /** Reads {@code modes.<id>} over the given fallback preset. */
    static BuildMode fromSection(String id, ConfigurationSection s, BuildMode d) {
        if (s == null) return d;
        return new BuildMode(id,
                s.getString("title", d.title),
                s.getString("eta", d.eta),
                effort(s.getString("plan", d.effortPlan)),
                effort(s.getString("build", d.effortBuild)),
                effort(s.getString("review", d.effortReview)),
                effort(s.getString("review-followup", d.effortReviewFollowup)),
                effort(s.getString("edit", d.effortEdit)),
                Math.max(0, s.getInt("verify-rounds", d.verifyRounds)),
                Math.max(0, s.getInt("edit-verify-rounds", d.editVerifyRounds)),
                Math.max(4000, Math.min(200000, s.getInt("max-tokens", d.maxTokens))),
                s.getBoolean("parallel-review", d.parallelReview));
    }

    /** Normalizes a reasoning effort from config ("none"/false -> off, "default" -> null = provider default). */
    static String effort(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("none") || s.equals("disabled") || s.equals("false") || s.equals("no")) return "off";
        if (s.isEmpty() || s.equals("default")) return null;
        return s;
    }

    /**
     * Maps user input (English/Russian, numbers 0..3) to a mode id, or null when unknown.
     * 0 / off / fast / без / быстр… → fast; 1 / low / light / лёгк… / мал… → light; 2 / std / стандарт… → standard;
     * 3 / extra / max / high / экстра… / макс… → extra.
     */
    public static String normalizeId(String in) {
        if (in == null) return null;
        String s = in.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (s.startsWith("--")) s = s.substring(2);
        return switch (s) {
            case "0", "fast", "off", "none", "nothink", "no-think", "no_think", "quick", "без", "без-думания",
                 "быстрый", "быстро", "выкл" -> FAST;
            case "1", "light", "low", "small", "lite", "легкий", "легкое", "маленькое", "малое", "мало" -> LIGHT;
            case "2", "standard", "std", "normal", "default", "стандарт", "стандартный", "обычный" -> STANDARD;
            case "3", "extra", "max", "high", "deep", "xhigh", "экстра", "экстрадумание", "экстра-думание", "максимум",
                 "макс", "глубокий" -> EXTRA;
            default -> {
                if (s.startsWith("без") || s.startsWith("быстр")) yield FAST;
                if (s.startsWith("легк") || s.startsWith("мал")) yield LIGHT;
                if (s.startsWith("стандарт") || s.startsWith("обыч")) yield STANDARD;
                if (s.startsWith("экстра") || s.startsWith("макс") || s.startsWith("глуб")) yield EXTRA;
                yield null;
            }
        };
    }

    /** One-line description for chat: efforts per role and review rounds. */
    public String describe() {
        return "план " + show(effortPlan) + ", этапы " + show(effortBuild) + ", проверка " + show(effortReview)
                + (verifyRounds > 1 ? " (+доп. раунды " + show(effortReviewFollowup) + ")" : "")
                + ", правки " + show(effortEdit) + ", раундов проверки до " + verifyRounds;
    }

    private static String show(String effort) {
        if (effort == null) return "по умолчанию";
        return switch (effort) {
            case "off" -> "без думания";
            case "low" -> "мало";
            case "medium" -> "средне";
            case "high" -> "много";
            case "xhigh" -> "очень много";
            case "max" -> "максимум";
            default -> effort;
        };
    }
}
