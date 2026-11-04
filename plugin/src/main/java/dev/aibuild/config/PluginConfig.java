package dev.aibuild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of config.yml. */
public final class PluginConfig {
    /** Candidate scripts written in parallel for a new build in mode {@code mode}. */
    public int scriptCandidates(String mode) {
        return scriptCandidates.getOrDefault(mode, 1);
    }

    /** Critic rounds after a script build in mode {@code mode}. */
    public int criticRounds(String mode) {
        return criticRounds.getOrDefault(mode, 1);
    }

    /** Seconds into a job after which critic rounds are skipped unless the build has critical defects (0 = never). */
    public int reviewDeadline(String mode) {
        return reviewDeadline.getOrDefault(mode, 0);
    }

    /** Whether the request is worked out into a full brief before anything is designed. */
    public boolean enhance(String mode) {
        return enhance.getOrDefault(mode, 0) > 0;
    }

    /** Whether a lead designer writes one design concept per script candidate before they are written. */
    public boolean concepts(String mode) {
        return concepts.getOrDefault(mode, 0) > 0;
    }

    /** Whether an interior designer furnishes the rooms found inside a build (in parallel with the sculptors). */
    public boolean interiors(String mode) {
        return interiors.getOrDefault(mode, 0) > 0;
    }

    /** Whether sculptors and the interior designer see what they built and may correct it once. */
    public boolean selfCheck(String mode) {
        return selfCheck.getOrDefault(mode, 0) > 0;
    }

    /** Studio: the wall clock of a new build in minutes, by mode. */
    public int studioMinutes(String mode) {
        return studioMinutes.getOrDefault(mode, 30);
    }

    /** Studio: parallel sessions for a new build (the blind judge picks between two or more), by mode. */
    public int studioFirstDrafts(String mode) {
        return studioFirstDrafts.getOrDefault(mode, 1);
    }

    public int studioSessions(String mode) {
        return studioSessions.getOrDefault(mode, 1);
    }

    /** Parts rewritten in parallel by sculptor calls after the art director's review (0 = off). */
    public int sculptors(String mode) {
        return sculptors.getOrDefault(mode, 0);
    }

    private static Map<String, Integer> perMode(org.bukkit.configuration.ConfigurationSection c, String path,
                                                Map<String, Integer> defaults, int min, int max) {
        Map<String, Integer> out = new java.util.HashMap<>(defaults);
        org.bukkit.configuration.ConfigurationSection s = c.getConfigurationSection(path);
        if (s != null) for (String k : s.getKeys(false)) out.put(k, Math.max(min, Math.min(max, s.getInt(k, out.getOrDefault(k, min)))));
        return Map.copyOf(out);
    }

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
    public final String createStrategy;
    /** Script strategy: repair calls after a failing script, and whether to fall back to the JSON-op program. */
    public final int scriptRepairAttempts;
    public final boolean scriptFallback;
    private final Map<String, Integer> scriptCandidates;
    private final Map<String, Integer> criticRounds;
    private final Map<String, Integer> reviewDeadline;
    private final Map<String, Integer> sculptors;
    private final Map<String, Integer> concepts;
    private final Map<String, Integer> enhance;
    /** Reasoning effort of the request enhancer. */
    public final String enhanceEffort;
    private final Map<String, Integer> interiors;
    private final Map<String, Integer> selfCheck;
    /** The create prompt asks the model to settle the layout in its thinking and write the script once. */
    public final boolean writeOnce;
    private final Map<String, Integer> studioMinutes;
    private final Map<String, Integer> studioSessions;
    private final Map<String, Integer> studioFirstDrafts;
    public final boolean studioVerify;
    /** Studio: minutes for an edit session, the turn fuse, helper sessions and their turns. */
    public final int studioEditMinutes, studioMaxTurns, studioHelperTurns;
    public final boolean studioDelegate;
    /** Reasoning effort of the lead designer's concept call. */
    public final String conceptEffort;
    /** Sculptors refine a part (keep its structure and kits, add craft) instead of redesigning it. */
    public final boolean sculptRefine;
    /** Reasoning effort of the art director and the part sculptors. */
    public final String sculptEffort;
    /** Seconds the sculptors get together; a sculptor still working then is stopped and its part left as it was. */
    public final int sculptTimeout;
    /** Repair mechanical defects (lights, loose attachments, blocked doors, decoration in walls) in code after review. */
    public final boolean finisher;
    /** Save a render and a quality row of every accepted stage in the job log. */
    public final boolean stageSnapshots;
    /** Send renders to the model (critic, judge) when the gateway delivers images. */
    public final boolean visionEnabled;
    /** Use the pairwise vision judge to pick candidates and accept critic revisions. */
    public final boolean visionJudge;
    public final int visionImageSize;
    public final String visionJudgeEffort;
    public final int adaptiveMaxRounds;
    public final String judgeEffort;
    public final boolean repairFailedOps;
    public final boolean autoOrient;
    public final boolean livePreview;

    /** Serve a one-time live view of every job (see {@code dev.aibuild.live}). */
    public final boolean liveView;
    /** Interface the live-view server listens on; loopback by default so it is never public by accident. */
    public final String liveBindHost;
    /** Live-view TCP port, 0 = pick a free one. */
    public final int livePort;
    /** Public base URL the player is given (tunnel or reverse proxy), empty = do not announce links. */
    public final String livePublicUrl;
    /** Concurrent browser event streams allowed. */
    public final int liveMaxViewers;
    /** Minutes a finished build's link keeps working before it is revoked. */
    public final int liveExpireMinutes;
    public final int maxConcurrentJobs;
    public final int maxExtent;
    public final int distance;
    public final boolean includeBlockList;

    public final int blocksPerTick;
    public final String foundationBlock;
    public final int maxFoundationDepth;

    public final boolean saveTranscripts;
    /** Derive render colours from the vanilla client textures (downloaded once from Mojang). */
    public final boolean renderTextures;

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
                    Math.max(4000, Math.min(dev.aibuild.ai.ChatRequest.MAX_TOKENS_LIMIT, c.getInt("api.max-tokens", std.maxTokens))),
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
        String rs = c.getString("generation.review-strategy", "adaptive").trim().toLowerCase(java.util.Locale.ROOT);
        reviewStrategy = rs.equals("fixed") ? "fixed" : "adaptive";
        String es = c.getString("generation.edit-strategy", "plan").trim().toLowerCase(java.util.Locale.ROOT);
        editStrategy = es.equals("direct") || es.equals("steps") ? es : "plan";
        String cs = c.getString("generation.create-strategy", "script").trim().toLowerCase(java.util.Locale.ROOT);
        createStrategy = cs.equals("staged") || cs.equals("compiled") || cs.equals("studio") ? cs : "script";
        scriptRepairAttempts = Math.max(0, Math.min(5, c.getInt("script.repair-attempts", 3)));
        scriptFallback = c.getBoolean("script.fallback-to-ops", true);
        scriptCandidates = perMode(c, "script.candidates", Map.of("fast", 1, "light", 1, "standard", 2, "extra", 3), 1, 5);
        criticRounds = perMode(c, "script.critic-rounds", Map.of("fast", 0, "light", 1, "standard", 1, "extra", 2), 0, 6);
        reviewDeadline = perMode(c, "script.review-deadline", Map.of("fast", 0, "light", 0, "standard", 1500, "extra", 0), 0, 7200);
        sculptors = perMode(c, "script.sculptors", Map.of("fast", 0, "light", 0, "standard", 3, "extra", 5), 0, 8);
        sculptEffort = c.getString("script.sculpt-effort", "high");
        concepts = perMode(c, "script.concepts", Map.of("fast", 0, "light", 0, "standard", 1, "extra", 1), 0, 1);
        interiors = perMode(c, "script.interiors", Map.of("fast", 0, "light", 1, "standard", 1, "extra", 1), 0, 1);
        selfCheck = perMode(c, "script.self-check", Map.of("fast", 0, "light", 0, "standard", 1, "extra", 1), 0, 1);
        writeOnce = c.getBoolean("script.write-once", true);
        studioMinutes = perMode(c, "studio.minutes", Map.of("fast", 8, "light", 15, "standard", 30, "extra", 60), 2, 240);
        studioSessions = perMode(c, "studio.sessions", Map.of("fast", 1, "light", 1, "standard", 1, "extra", 2), 1, 4);
        studioFirstDrafts = perMode(c, "studio.first-drafts", Map.of("fast", 1, "light", 1, "standard", 2, "extra", 3), 1, 4);
        studioVerify = c.getBoolean("studio.verify", true);
        studioEditMinutes = Math.clamp(c.getInt("studio.edit-minutes", 15), 2, 240);
        studioMaxTurns = Math.clamp(c.getInt("studio.max-turns", 40), 2, 200);
        studioHelperTurns = Math.clamp(c.getInt("studio.helper-turns", 12), 1, 60);
        studioDelegate = c.getBoolean("studio.delegate", true);
        conceptEffort = BuildMode.effort(c.getString("script.concept-effort", "high"));
        enhance = perMode(c, "script.enhance", Map.of("fast", 0, "light", 1, "standard", 1, "extra", 1), 0, 1);
        enhanceEffort = BuildMode.effort(c.getString("script.enhance-effort", "high"));
        sculptRefine = !"rewrite".equalsIgnoreCase(c.getString("script.sculpt-style", "refine").trim());
        sculptTimeout = Math.clamp(c.getInt("script.sculpt-timeout", 900), 30, 3600);
        finisher = c.getBoolean("script.finisher", true);
        stageSnapshots = c.getBoolean("debug.stage-snapshots", true);
        visionEnabled = c.getBoolean("vision.enabled", true);
        visionJudge = c.getBoolean("vision.judge", true);
        visionImageSize = Math.clamp(c.getInt("vision.image-size", 1536), 256, 2048);
        visionJudgeEffort = BuildMode.effort(c.getString("vision.judge-effort", "high"));
        adaptiveMaxRounds = Math.max(1, Math.min(12, c.getInt("generation.adaptive-max-rounds", 6)));
        judgeEffort = BuildMode.effort(c.getString("bench.judge-effort", "high"));
        autoOrient = c.getBoolean("generation.auto-orient", true);
        livePreview = c.getBoolean("generation.live-preview", true);
        liveView = c.getBoolean("generation.live-view.enabled", true);
        liveBindHost = c.getString("generation.live-view.bind", "127.0.0.1");
        livePort = Math.clamp(c.getInt("generation.live-view.port", 0), 0, 65535);
        livePublicUrl = c.getString("generation.live-view.public-url", "");
        liveMaxViewers = Math.clamp(c.getInt("generation.live-view.max-viewers", 8), 1, 64);
        liveExpireMinutes = Math.clamp(c.getInt("generation.live-view.expire-minutes", 15), 1, 1440);
        maxConcurrentJobs = Math.max(1, c.getInt("generation.max-concurrent-jobs", 2));
        maxExtent = Math.max(8, c.getInt("generation.max-extent", 160));
        distance = Math.max(0, c.getInt("generation.distance", 3));
        includeBlockList = c.getBoolean("generation.include-block-list", true);
        renderTextures = !"off".equalsIgnoreCase(c.getString("render.textures", "auto"));

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
