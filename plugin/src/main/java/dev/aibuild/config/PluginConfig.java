package dev.aibuild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of config.yml. */
public final class PluginConfig {
    public final String apiKey;
    public final String baseUrl;
    public final String model;
    public final String transport;
    public final Double temperature;
    public final int connectTimeout;
    public final int idleTimeout;
    public final int maxRetries;
    public final String cliVersion;

    /** Thinking modes by id (fast, light, standard, extra), in display order. */
    public final Map<String, BuildMode> modes;
    /** Mode used when the player did not choose one. */
    public final String defaultMode;
    public final int minScore;
    /** "adaptive" (AI decides when verification is complete, checklist driven) or "fixed" (N rounds). */
    public final String reviewStrategy;
    /** "planned" (edit plan + steps) or "direct" (one call). */
    public final String editStrategy;
    public final int adaptiveMaxRounds;
    public final String judgeEffort;
    public final boolean repairFailedOps;
    public final boolean autoOrient;
    public final boolean livePreview;
    public final int maxConcurrentJobs;
    public final int maxExtent;
    public final int distance;
    public final boolean includeBlockList;

    public final int blocksPerTick;
    public final String foundationBlock;
    public final int maxFoundationDepth;

    public final boolean saveTranscripts;

    public PluginConfig(FileConfiguration c) {
        String key = c.getString("api.key", "");
        if (key == null || key.isBlank()) key = System.getenv().getOrDefault("COMMANDCODE_API_KEY", System.getenv().getOrDefault("COMMAND_CODE_API_KEY", ""));
        apiKey = key.trim();
        baseUrl = trimSlash(c.getString("api.base-url", "https://api.commandcode.ai"));
        model = c.getString("api.model", "deepseek/deepseek-v4.1-flash");
        transport = c.getString("api.transport", "auto");
        double t = c.getDouble("api.temperature", 0.6);
        temperature = t < 0 ? null : t;
        connectTimeout = Math.max(5, c.getInt("api.connect-timeout-seconds", 20));
        idleTimeout = Math.max(30, c.getInt("api.idle-timeout-seconds", 240));
        maxRetries = Math.max(0, c.getInt("api.max-retries", 3));
        cliVersion = c.getString("api.cli-version", "auto");

        // ---- thinking modes: built-in presets, legacy single-mode keys feed "standard", then modes.<id> overrides
        Map<String, BuildMode> m = BuildMode.defaults();
        BuildMode std = m.get(BuildMode.STANDARD);
        if (c.isConfigurationSection("api.reasoning-effort") || c.contains("generation.verify-rounds")) {
            std = new BuildMode(BuildMode.STANDARD, std.title, std.eta,
                    BuildMode.effort(c.getString("api.reasoning-effort.plan", std.effortPlan)),
                    BuildMode.effort(c.getString("api.reasoning-effort.build", std.effortBuild)),
                    BuildMode.effort(c.getString("api.reasoning-effort.review", std.effortReview)),
                    BuildMode.effort(c.getString("api.reasoning-effort.review-followup", std.effortReviewFollowup)),
                    BuildMode.effort(c.getString("api.reasoning-effort.edit", std.effortEdit)),
                    Math.max(0, c.getInt("generation.verify-rounds", std.verifyRounds)),
                    Math.max(0, c.getInt("generation.edit-verify-rounds", std.editVerifyRounds)),
                    Math.max(4000, Math.min(200000, c.getInt("api.max-tokens", std.maxTokens))),
                    c.getBoolean("generation.parallel-review", std.parallelReview));
            m.put(BuildMode.STANDARD, std);
        }
        ConfigurationSection ms = c.getConfigurationSection("modes");
        if (ms != null) {
            for (String id : ms.getKeys(false)) {
                String norm = BuildMode.normalizeId(id);
                String modeId = norm != null ? norm : id.toLowerCase(java.util.Locale.ROOT);
                BuildMode base = m.getOrDefault(modeId, m.get(BuildMode.STANDARD));
                m.put(modeId, BuildMode.fromSection(modeId, ms.getConfigurationSection(id), base));
            }
        }
        modes = Collections.unmodifiableMap(new LinkedHashMap<>(m));
        String dm = BuildMode.normalizeId(c.getString("generation.default-mode", BuildMode.STANDARD));
        defaultMode = dm != null && modes.containsKey(dm) ? dm : BuildMode.STANDARD;

        minScore = c.getInt("generation.min-score", 86);
        repairFailedOps = c.getBoolean("generation.repair-failed-ops", true);
        String rs = c.getString("generation.review-strategy", "fixed").trim().toLowerCase(java.util.Locale.ROOT);
        reviewStrategy = rs.equals("adaptive") ? "adaptive" : "fixed";
        String es = c.getString("generation.edit-strategy", "plan").trim().toLowerCase(java.util.Locale.ROOT);
        editStrategy = es.equals("direct") || es.equals("steps") ? es : "plan";
        adaptiveMaxRounds = Math.max(1, Math.min(12, c.getInt("generation.adaptive-max-rounds", 6)));
        judgeEffort = BuildMode.effort(c.getString("bench.judge-effort", "high"));
        autoOrient = c.getBoolean("generation.auto-orient", true);
        livePreview = c.getBoolean("generation.live-preview", true);
        maxConcurrentJobs = Math.max(1, c.getInt("generation.max-concurrent-jobs", 2));
        maxExtent = Math.max(8, c.getInt("generation.max-extent", 96));
        distance = Math.max(0, c.getInt("generation.distance", 3));
        includeBlockList = c.getBoolean("generation.include-block-list", true);

        blocksPerTick = Math.max(100, c.getInt("placement.blocks-per-tick", 4000));
        foundationBlock = c.getString("placement.foundation-block", "cobblestone");
        maxFoundationDepth = Math.max(0, c.getInt("placement.max-foundation-depth", 12));

        saveTranscripts = c.getBoolean("debug.save-transcripts", true);
    }

    /** The mode for an id (any alias), falling back to the default mode. */
    public BuildMode mode(String idOrAlias) {
        String n = BuildMode.normalizeId(idOrAlias);
        BuildMode b = n == null ? null : modes.get(n);
        if (b == null && idOrAlias != null) b = modes.get(idOrAlias.toLowerCase(java.util.Locale.ROOT));
        return b != null ? b : modes.get(defaultMode);
    }

    private static String trimSlash(String s) {
        if (s == null) return "https://api.commandcode.ai";
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
