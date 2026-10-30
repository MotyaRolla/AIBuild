package dev.aibuild.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/** Self-check: config.yml modes parse, aliases resolve, legacy single-mode configs still work. */
public final class ModesCheck {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(a[0]));
        PluginConfig c = new PluginConfig(y);
        check(c.modes.keySet().equals(java.util.Set.of("fast", "light", "standard", "extra")), "4 modes: " + c.modes.keySet());
        check(c.defaultMode.equals("standard"), "default standard");
        BuildMode f = c.modes.get("fast");
        check("off".equals(f.effortPlan) && "off".equals(f.effortBuild) && "off".equals(f.effortReview) && "off".equals(f.effortEdit), "fast all off");
        BuildMode l = c.modes.get("light");
        check("low".equals(l.effortBuild) && "off".equals(l.effortReview), "light: low build, no-think review");
        BuildMode s = c.modes.get("standard");
        check("low".equals(s.effortBuild) && "low".equals(s.effortReview) && "off".equals(s.effortReviewFollowup) && s.verifyRounds == 3, "standard = previous behaviour");
        BuildMode e = c.modes.get("extra");
        check("high".equals(e.effortBuild) && "high".equals(e.effortReview) && e.maxTokens == 200000, "extra high + big budget");
        for (String[] t : new String[][]{{"0", "fast"}, {"без", "fast"}, {"быстрый", "fast"}, {"--off", "fast"}, {"1", "light"},
                {"лёгкое", "light"}, {"маленькое", "light"}, {"low", "light"}, {"std", "standard"}, {"Стандарт", "standard"},
                {"3", "extra"}, {"экстра", "extra"}, {"Экстрадумание", "extra"}, {"max", "extra"}}) {
            check(t[1].equals(BuildMode.normalizeId(t[0])), "alias " + t[0] + " -> " + t[1] + " got " + BuildMode.normalizeId(t[0]));
        }
        check(c.mode("unknown").id.equals("standard"), "unknown -> default");
        // legacy config (no modes section): standard built from api.reasoning-effort
        YamlConfiguration old = new YamlConfiguration();
        old.set("api.reasoning-effort.build", "medium");
        old.set("api.reasoning-effort.review", false); // unquoted off in YAML -> boolean false
        old.set("generation.verify-rounds", 1);
        PluginConfig oc = new PluginConfig(old);
        check("medium".equals(oc.modes.get("standard").effortBuild) && "off".equals(oc.modes.get("standard").effortReview)
                && oc.modes.get("standard").verifyRounds == 1, "legacy keys feed standard");
        check(oc.modes.size() == 4, "legacy config still has all 4 modes");
        System.out.println("ModesCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
