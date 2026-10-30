package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/** Self-check for the adaptive verification checklist. */
public final class ChecklistCheck {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        var plan = JsonParser.parseString("""
            {"title":"Дом","exterior":"сад и дорожка","details":["ставни у окон","фонарь у двери"],
             "layout":[{"level":"1 этаж","rooms":[{"name":"кухня","area":"x1-4,z1-4","furnishing":"печь, стол"}]}]}""").getAsJsonObject();
        Checklist c = Checklist.forCreate(plan);
        check(c.size() == 10 + 1 + 1 + 2, "core + room + exterior + 2 details: " + c.size());
        check(!c.allVerified() && c.verified() == 0, "starts open");
        check(c.openAreas().containsAll(java.util.List.of("structure", "exterior", "interior")), "open areas " + c.openAreas());
        int failed = c.apply(JsonParser.parseString("[{\"id\":\"C1\",\"ok\":true},{\"id\":\"2\",\"ok\":false,\"note\":\"hole at 3,9,4\"},{\"id\":\"C99\",\"ok\":true}]").getAsJsonArray(), 1);
        check(failed == 1 && c.verified() == 1, "apply ok/failed, ids normalized, unknown ignored");
        check(c.render("structure").contains("C2 [structure] RECHECK") && c.render("structure").contains("hole at 3,9,4"), "recheck rendered with note");
        check(!c.render("interior").contains("C1 "), "area filter hides other areas");
        JsonArray all = new JsonArray();
        for (int i = 1; i <= c.size(); i++) all.add(JsonParser.parseString("{\"id\":\"C" + i + "\",\"ok\":true}"));
        c.apply(all, 2);
        check(c.allVerified() && c.openAreas().isEmpty(), "all verified");
        Checklist e = Checklist.forEdit("добавь башню", JsonParser.parseString("[\"башня 5x5 у восточной стены\"]").getAsJsonArray(),
                JsonParser.parseString("[\"крыша дома\"]").getAsJsonArray());
        check(e.size() == 1 + 1 + 1 + 3 && e.openAreas().equals(java.util.List.of("change")), "edit checklist " + e.size() + " " + e.openAreas());
        System.out.println("ChecklistCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
