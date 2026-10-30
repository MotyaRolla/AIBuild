package dev.aibuild.model;

import java.util.Locale;

/**
 * Cardinal directions in model space. Coordinate system: +x = east, +y = up, +z = south.
 */
public enum Dir {
    NORTH(0, 0, -1),
    EAST(1, 0, 0),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    public static final Dir[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};

    public final int dx, dy, dz;

    Dir(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /** Lower-case Minecraft name, e.g. "north". */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Dir opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            case UP -> DOWN;
            case DOWN -> UP;
        };
    }

    /** Clockwise rotation when looking from above (N -> E -> S -> W). Vertical dirs unchanged. */
    public Dir cw() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    /** Counter-clockwise rotation when looking from above (N -> W -> S -> E). */
    public Dir ccw() {
        return switch (this) {
            case NORTH -> WEST;
            case WEST -> SOUTH;
            case SOUTH -> EAST;
            case EAST -> NORTH;
            default -> this;
        };
    }

    public boolean isHorizontal() {
        return dy == 0;
    }

    /** 'x', 'y' or 'z'. */
    public char axis() {
        return dx != 0 ? 'x' : dy != 0 ? 'y' : 'z';
    }

    /** Parses "north"/"n"/"NORTH" etc. Returns null when unknown. */
    public static Dir parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n", "-z" -> NORTH;
            case "south", "s", "+z", "z" -> SOUTH;
            case "east", "e", "+x", "x" -> EAST;
            case "west", "w", "-x" -> WEST;
            case "up", "u", "+y", "y" -> UP;
            case "down", "d", "-y" -> DOWN;
            default -> null;
        };
    }
}
