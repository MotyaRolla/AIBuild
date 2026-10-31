package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verification checklist for the adaptive review loop. Items come from the design brief (or the edit plan's
 * acceptance criteria) plus a fixed core; reviewers mark items as verified (ok) or failed (fixed, needs recheck).
 * Verification is complete when every item is OK and every reviewer declares itself done. Thread-safe.
 */
public final class Checklist {
    public enum Status {OPEN, RECHECK, OK}

    public static final class Item {
        public final String id;
        public final String text;
        /** structure | exterior | interior | change | any */
        public final String area;
        Status status = Status.OPEN;
        String note = "";
        int round;
        int fails;

        Item(String id, String text, String area) {
            this.id = id;
            this.text = text;
            this.area = area;
        }
    }

    private final Map<String, Item> items = new LinkedHashMap<>();

    private void add(String text, String area) {
        if (text == null || text.isBlank()) return;
        String id = "C" + (items.size() + 1);
        items.put(id, new Item(id, Prompts.escape(text), area));
    }

    /** Checklist for a new build: fixed core + brief details, rooms and exterior. */
    public static Checklist forCreate(JsonObject plan) {
        Checklist c = new Checklist();
        c.add("Request compliance: building type, style, EXACT main-body size, every requested feature present", "structure");
        c.add("Shell closed: walls and roofs without holes, nothing floating, foundations reach the ground", "structure");
        c.add("Stairs and slabs: facing/half correct (roofs uphill, skirts/sills/eaves back to the wall, chairs to the table), every one supported", "structure");
        c.add("Doors, trapdoors, torches, lanterns, ladders, chains, beds, logs: facing correct, each attached to a solid support, passages free", "structure");
        c.add("Composition: intentional massing and proportions, legible entrance, coherent window rhythm; facade depth and palette fit the requested style, without arbitrary material or decoration quotas", "exterior");
        c.add("Roof/form: silhouette and roof-to-body proportions fit the design; required roofs closed, junctions clean; flat roofs and restrained detailing are valid when appropriate", "exterior");
        c.add("Site: build meets the ground convincingly, entrance connects to surroundings; landscape and props serve the brief rather than clutter it", "exterior");
        c.add("Function: required interior spaces furnished for their purpose; non-building objects instead have complete recognizable requested parts; do not invent rooms", "interior");
        c.add("Lighting: required usable interior spaces lit, without adding unnecessary fixtures to non-interior objects", "interior");
        c.add("Access: required doors, furniture and floors reachable with free approach cells and headroom; preserve deliberate solid sculptures and inaccessible decorative forms", "interior");
        if (plan != null) {
            for (JsonElement l : Json.arr(plan, "layout")) {
                if (!l.isJsonObject()) continue;
                JsonObject lv = l.getAsJsonObject();
                for (JsonElement r : Json.arr(lv, "rooms")) {
                    if (!r.isJsonObject()) continue;
                    JsonObject ro = r.getAsJsonObject();
                    c.add("Room '" + Json.str(ro, "name", "?") + "' (" + Json.str(lv, "level", "") + ", " + Json.str(ro, "area", "")
                            + "): " + Json.str(ro, "furnishing", "furnished"), "interior");
                }
            }
            String ext = Json.str(plan, "exterior", null);
            if (ext != null && !ext.isBlank()) c.add("Exterior as planned: " + ext, "exterior");
            int n = 0;
            for (JsonElement d : Json.arr(plan, "details")) {
                if (n++ >= 30) break;
                if (d.isJsonPrimitive()) c.add("Detail: " + d.getAsString(), "any");
            }
        }
        return c;
    }

    /** Checklist for an edit: acceptance criteria and preservation constraints from the edit plan. */
    public static Checklist forEdit(String change, JsonArray acceptance, JsonArray preserve) {
        Checklist c = new Checklist();
        c.add("The requested change is fully implemented: " + change, "change");
        for (JsonElement a : acceptance) if (a.isJsonPrimitive()) c.add(a.getAsString(), "change");
        for (JsonElement p : preserve) if (p.isJsonPrimitive()) c.add("Preserved unchanged: " + p.getAsString(), "change");
        c.add("Everything not related to the change is intact (no collateral damage)", "change");
        c.add("Structure still sound: closed shell, nothing floating, attachments supported, orientations correct", "change");
        c.add("Lighting and access still fine around the changed area", "change");
        return c;
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized int verified() {
        int n = 0;
        for (Item i : items.values()) if (i.status == Status.OK) n++;
        return n;
    }

    /** True when every still-open item has failed at least {@code n} times (only stubborn/nit-pick items remain). */
    public synchronized boolean onlyStubborn(int n) {
        for (Item i : items.values()) if (i.status != Status.OK && i.fails < n) return false;
        return true;
    }

    public synchronized boolean allVerified() {
        for (Item i : items.values()) if (i.status != Status.OK) return false;
        return true;
    }

    public synchronized boolean allVerified(String area) {
        for (Item i : items.values()) if (owns(i, area) && i.status != Status.OK) return false;
        return true;
    }

    /** Areas that still have OPEN/RECHECK items ("any" counts as structure). */
    public synchronized List<String> openAreas() {
        List<String> out = new ArrayList<>();
        for (Item i : items.values()) {
            if (i.status == Status.OK) continue;
            String a = i.area.equals("any") ? "structure" : i.area;
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    /** Applies reviewer results: [{"id":"C3","ok":true,"note":"..."}]. Returns number of failed (ok=false) items. */
    public synchronized int apply(JsonArray checked, int round) {
        return apply(checked, round, null);
    }

    public synchronized int apply(JsonArray checked, int round, String area) {
        int failed = 0;
        for (JsonElement e : checked) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = Json.str(o, "id", "").trim().toUpperCase(Locale.ROOT);
            if (!id.startsWith("C")) id = "C" + id;
            Item it = items.get(id);
            if (it == null) continue;
            if (!owns(it, area)) continue;
            boolean ok = Json.bool(o, "ok", false);
            it.status = ok ? Status.OK : Status.RECHECK;
            if (!ok) it.fails++;
            it.note = Prompts.escape(Json.str(o, "note", ""));
            if (it.note.length() > 160) it.note = it.note.substring(0, 157) + "...";
            it.round = round;
            if (!ok) failed++;
        }
        return failed;
    }

    /** Text for the prompt, optionally filtered to an area (always includes "any"). */
    public synchronized String render(String area) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items.values()) {
            if (!owns(i, area)) continue;
            sb.append(i.id).append(" [").append(i.area).append("] ").append(i.status).append(": ").append(i.text);
            if (!i.note.isBlank()) sb.append(" — last note (round ").append(i.round).append("): ").append(i.note);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean owns(Item item, String area) {
        if (area == null || area.equals("all") || area.equals("change")) return true;
        return item.area.equals(area) || item.area.equals("any") && area.equals("structure");
    }

    public synchronized String progress() {
        return verified() + "/" + size();
    }
}
