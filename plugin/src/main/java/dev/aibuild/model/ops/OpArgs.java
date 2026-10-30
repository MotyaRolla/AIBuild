package dev.aibuild.model.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Lenient readers for the JSON values produced by the LLM.
 *
 * <p>All helpers accept null/missing input gracefully (returning a default or {@code null}) and throw
 * {@link IllegalArgumentException} with an LLM-readable message for values that are present but unusable.
 * Numbers may be JSON numbers or numeric strings and are rounded to integers; booleans accept the usual
 * textual spellings.</p>
 */
final class OpArgs {
    private OpArgs() {
    }

    /** First present, non-null value among the given keys (checked in order), else {@code null}. */
    static JsonElement field(JsonObject o, String... names) {
        if (o == null) return null;
        for (String n : names) {
            JsonElement e = o.get(n);
            if (e != null && !e.isJsonNull()) return e;
        }
        return null;
    }

    /** First present string value among the keys, else {@code def}. Non-primitive values are ignored. */
    static String str(JsonObject o, String def, String... names) {
        JsonElement e = field(o, names);
        if (e == null || !e.isJsonPrimitive()) return def;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isString() && !p.isNumber()) return def;
        String s = p.getAsString();
        return s == null ? def : s.trim();
    }

    /** First present integer value among the keys, else {@code null}. */
    static Integer integer(JsonObject o, String... names) {
        JsonElement e = field(o, names);
        if (e == null) return null;
        if (!e.isJsonPrimitive()) {
            throw new IllegalArgumentException("field '" + names[0] + "' must be a number");
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean() ? 1 : 0;
        return toInt(p, "'" + names[0] + "'");
    }

    /** First present integer value among the keys, else {@code def}. */
    static int integer(JsonObject o, int def, String... names) {
        Integer v = integer(o, names);
        return v == null ? def : v;
    }

    /** First present double value among the keys, else {@code null}. */
    static Double number(JsonObject o, String... names) {
        JsonElement e = field(o, names);
        if (e == null) return null;
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("field '" + names[0] + "' must be a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) throw new IllegalArgumentException("field '" + names[0] + "' must be a number");
        return toDouble(p, "'" + names[0] + "'");
    }

    /** First present boolean value among the keys, else {@code def}. Accepts true/false, yes/no, on/off, 1/0. */
    static boolean bool(JsonObject o, boolean def, String... names) {
        JsonElement e = field(o, names);
        if (e == null) return def;
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("field '" + names[0] + "' must be true or false");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble() != 0.0;
        String s = p.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "true", "yes", "on", "1", "y" -> true;
            case "false", "no", "off", "0", "n" -> false;
            default -> throw new IllegalArgumentException("field '" + names[0] + "' must be true or false, got '" + s + "'");
        };
    }

    /**
     * Parses a coordinate triple. Accepts {@code [x,y,z]}, {@code [x,z]} (y defaults to 0),
     * {@code {"x":..,"y":..,"z":..}} (y optional) and {@code "x,y,z"} strings.
     *
     * @param label field name used in error messages
     * @throws IllegalArgumentException when the value is missing or not a usable coordinate
     */
    static int[] point(JsonElement e, String label) {
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("missing coordinate '" + label + "'");
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() < 2) {
                throw new IllegalArgumentException("coordinate '" + label + "' must be [x,y,z], got " + e);
            }
            if (a.size() == 2) {
                return new int[]{num(a.get(0), label + "[0]"), 0, num(a.get(1), label + "[2]")};
            }
            return new int[]{num(a.get(0), label + "[0]"), num(a.get(1), label + "[1]"), num(a.get(2), label + "[2]")};
        }
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            JsonElement xe = objField(o, "x");
            JsonElement ze = objField(o, "z");
            if (xe == null || ze == null) {
                throw new IllegalArgumentException("coordinate '" + label + "' must have x and z: " + e);
            }
            JsonElement ye = objField(o, "y");
            return new int[]{
                    num(xe, label + ".x"),
                    ye == null ? 0 : num(ye, label + ".y"),
                    num(ze, label + ".z")};
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString().trim();
            List<String> parts = new ArrayList<>();
            for (String p : s.split("[,\\s]+")) if (!p.isBlank()) parts.add(p);
            if (parts.size() == 3) {
                return new int[]{parseInt(parts.get(0), label), parseInt(parts.get(1), label), parseInt(parts.get(2), label)};
            }
            if (parts.size() == 2) {
                return new int[]{parseInt(parts.get(0), label), 0, parseInt(parts.get(1), label)};
            }
            throw new IllegalArgumentException("coordinate '" + label + "' must be [x,y,z], got \"" + s + "\"");
        }
        throw new IllegalArgumentException("coordinate '" + label + "' must be [x,y,z] or {x,y,z}, got " + e);
    }

    /** Parses a coordinate triple, returning {@code null} instead of throwing when absent. */
    static int[] optionalPoint(JsonObject o, String... names) {
        JsonElement e = field(o, names);
        if (e == null) return null;
        return point(e, names[0]);
    }

    /**
     * Reads an optional inclusive region from the op: {@code from}/{@code to} alias pairs, or a
     * {@code region}/{@code box}/{@code area} array of two corners (or six numbers).
     *
     * @return {@code {from, to}} in the order given, or {@code null} when the op has no region fields
     */
    static int[][] region(JsonObject op) {
        JsonElement fromEl = field(op, "from", "start", "p1", "pos1", "a", "min", "corner1", "corner");
        JsonElement toEl = field(op, "to", "end", "p2", "pos2", "b", "max", "corner2");
        if (fromEl != null && toEl != null) {
            return new int[][]{point(fromEl, "from"), point(toEl, "to")};
        }
        JsonElement box = field(op, "region", "box", "area", "bounds", "rect");
        if (box != null) {
            int[][] r = boxPoints(box);
            if (r != null) return r;
        }
        if (fromEl != null || toEl != null) {
            throw new IllegalArgumentException("missing '" + (fromEl == null ? "from" : "to") + "' corner");
        }
        return null;
    }

    private static int[][] boxPoints(JsonElement box) {
        if (box.isJsonArray()) {
            JsonArray a = box.getAsJsonArray();
            if (a.size() == 2) {
                return new int[][]{point(a.get(0), "region[0]"), point(a.get(1), "region[1]")};
            }
            if (a.size() == 6) {
                return new int[][]{
                        new int[]{num(a.get(0), "region[0]"), num(a.get(1), "region[1]"), num(a.get(2), "region[2]")},
                        new int[]{num(a.get(3), "region[3]"), num(a.get(4), "region[4]"), num(a.get(5), "region[5]")}};
            }
            throw new IllegalArgumentException("'region' must contain two corners or six numbers");
        }
        if (box.isJsonObject()) {
            JsonObject o = box.getAsJsonObject();
            JsonElement f = field(o, "from", "start", "p1", "min", "a");
            JsonElement t = field(o, "to", "end", "p2", "max", "b");
            if (f != null && t != null) return new int[][]{point(f, "region.from"), point(t, "region.to")};
        }
        return null;
    }

    private static JsonElement objField(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e != null && !e.isJsonNull()) return e;
        for (String k : o.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                JsonElement v = o.get(k);
                if (v != null && !v.isJsonNull()) return v;
            }
        }
        return null;
    }

    private static int num(JsonElement e, String label) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException(label + " must be a number, got " + e);
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) throw new IllegalArgumentException(label + " must be a number, got " + p);
        return toInt(p, label);
    }

    private static int toInt(JsonPrimitive p, String label) {
        double d = toDouble(p, label);
        long r = Math.round(d);
        if (r > Integer.MAX_VALUE || r < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(label + " is out of range");
        }
        return (int) r;
    }

    private static double toDouble(JsonPrimitive p, String label) {
        double d;
        if (p.isNumber()) {
            d = p.getAsDouble();
        } else if (p.isString()) {
            String s = p.getAsString().trim();
            if (s.isEmpty()) throw new IllegalArgumentException(label + " is empty");
            try {
                d = Double.parseDouble(s);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(label + ": '" + s + "' is not a number");
            }
        } else {
            throw new IllegalArgumentException(label + " must be a number");
        }
        if (!Double.isFinite(d)) throw new IllegalArgumentException(label + " must be a finite number");
        return d;
    }

    private static int parseInt(String s, String label) {
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("coordinate '" + label + "': '" + s + "' is not a number");
        }
    }

    /** Human readable JSON type name for error messages. */
    static String typeName(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonArray()) return "array";
        if (e.isJsonObject()) return "object";
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "boolean";
        if (p.isNumber()) return "number";
        return "string";
    }
}
