package dev.aibuild.live;

import java.nio.charset.StandardCharsets;

/**
 * Wire format of one projected build, served as {@code /live/<token>/model.bin}.
 *
 * <p>The page draws the build with WebGL/canvas on the client, so the model has to cross the wire. Text would
 * be far too large (a 160³ build is tens of thousands of cells); this is a flat little-endian binary blob:
 * a 16-byte magic header, a JSON blob (block palette and textures) and one 16-byte record per cell. No
 * per-cell colours: the page indexes the palette, exactly like the in-game render.</p>
 *
 * <h2>Layout</h2>
 * <pre>
 * magic     8 bytes  "AIBLV02X"
 * payload   4 bytes  LE uint32  byte length of the JSON payload, padded to a multiple of 4
 * atlas     4 bytes  LE uint32  byte length of the PNG atlas (0 when the server has no textures)
 * version   1 byte   format version, currently 2 (1 had no atlas section)
 * reserved  3 bytes  zero
 * payload   JSON, UTF-8, padded to a multiple of 4:
 *           {
 *             "why":  "stream" | "stage" | "critic" | "final",
 *             "at":   1712345678901,          // epoch millis of the observation
 *             "seed": 12345,                  // job seed, so random details can be re-rolled client side
 *             "blocks": [{"n": "oak_planks", "c": 14532037, "t": [top, side, bottom]}, …],
 *             "materials": [{"n": "oak", "c": 14532037}, …],   // legend for the code view
 *             "bounds": [minX, minY, minZ, maxX, maxY, maxZ],
 *             "stats":  {"cells": 4213, "parts": 12},
 *             "parts":  [{"name": "house/roof", "cells": 640, "bounds": [...]}, …],
 *             "atlas":  {"cols": 64, "size": 16,       // tiling of the tile image
 *                        "url": "/live/<token>/atlas.png"}  // where to fetch it (absent = embedded below)
 *           }
 *           t = tile indices into that atlas: [top, side, bottom]; -1 when the block has no texture.
 * atlas     PNG bytes, {@code atlas} long. Normally 0: the atlas is ~190 KB and identical for every frame, so
 *           the pack only points at its cacheable URL and the page fetches it once. It is embedded only for
 *           self-contained packs (tests, offline renders), when the payload's atlas object has no {@code url}.
 *           With neither, the page falls back to flat palette colours.
 * cells     n × 16 bytes, LE:
 *           offset 0  int16 x, int16 y, int16 z  coordinates in model space (block corner, y floor of cell)
 *           offset 6  uint8 shape               {@link Shapes}
 *           offset 7  uint8 facing              0..7, {@link Facing}, only meaningful for non-cube shapes;
 *                                               stairs add {@link Facing#UPSIDE_DOWN}
 *           offset 8  uint16 block              index into payload.blocks
 *           offset 10 uint32 flags              {@link Flags}
 *           offset 14 int16 part                index into payload.parts, -1 when unknown
 *                                               (a model has at most a few hundred parts, so 16 bits is plenty;
 *                                               the record stays 16 bytes, which keeps the page's decode simple)
 * </pre>
 *
 * <p>The atlas PNG is the client's block atlas: a grid of 16×16 tiles, {@code cols} per row, addressed by the
 * tile indices in the palette. It travels inside the pack rather than being fetched per tile, so one request
 * already yields a page that draws with real Minecraft textures. It is Mojang's artwork: it is sent only to the
 * holder of the one-time token and the viewer never stores it.</p>
 *
 * <p>Writers must sort cells by y then x then z so the page can grow an already-drawn build incrementally
 * without flicker. Readers ignore trailing bytes they do not know (forward compatible).</p>
 *
 * <p>The payload length is in the header rather than derived from the JSON, because cell records are arbitrary
 * bytes and may themselves contain {@code {} — a reader that scanned for the last brace would decode garbage.</p>
 */
public final class ModelPack {

    /** First eight bytes of every pack; the last character is bumped when the layout changes incompatibly. */
    public static final byte[] MAGIC = "AIBLV02X".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 2;
    /** Header (magic + payload length + atlas length + version + reserved) in bytes. */
    public static final int HEADER_BYTES = 20;
    /** Size of one cell record in bytes. */
    public static final int CELL_BYTES = 16;
    /** Tile edge in pixels of the block atlas. */
    public static final int ATLAS_TILE = 16;
    /** Default number of tiles per atlas row, matching {@code BlockTextures}. */
    public static final int ATLAS_COLS = 64;
    /** Coordinates are packed as int16, so a model axis may not exceed this. */
    public static final int MAX_COORD = Short.MAX_VALUE;

    private ModelPack() {
    }

    /** How the server projects one block's box into the cell record; mirrored by the page's renderer. */
    public static final class Shapes {
        /** A full cube (default). */
        public static final int CUBE = 0;
        /** Bottom slab: lower half of the cell. */
        public static final int SLAB_BOTTOM = 1;
        /** Top slab: upper half. */
        public static final int SLAB_TOP = 2;
        /** Stairs whose tall side is at {@link Facing}. */
        public static final int STAIRS = 3;
        /** Fence / wall post: a thin central column. */
        public static final int POST = 4;
        /** Full-height pane along one axis (glass panes, iron bars). */
        public static final int PANE = 5;
        /** Cross-shaped plant (flowers, saplings, torches). */
        public static final int CROSS = 6;
        /** Flat plate on the floor (carpet, pressure plate, lily pad). */
        public static final int PLATE = 7;
        /** Anything else: drawn as a cube, optionally transparent. */
        public static final int OTHER = 8;

        private Shapes() {
        }
    }

    /** Facing of a non-cube shape, clockwise from north (matching {@link dev.aibuild.model.Dir}). */
    public static final class Facing {
        public static final int NORTH = 0;
        public static final int EAST = 1;
        public static final int SOUTH = 2;
        public static final int WEST = 3;
        /** Upward-facing (a top slab in stair form, a torch on a ceiling). */
        public static final int UP = 4;
        public static final int DOWN = 5;
        public static final int NONE = 7;
        /** Added to a stairs facing when the stairs are upside down ({@code half=top}). */
        public static final int UPSIDE_DOWN = 8;

        private Facing() {
        }
    }

    /** Bit flags of one cell. */
    public static final class Flags {
        /** The block is air: the cell was explicitly cleared (the page skips it, the diff still shows it). */
        public static final int AIR = 1;
        /** Not a full cube: the page draws the shape with alpha where the block is see-through. */
        public static final int TRANSLUCENT = 1 << 1;
        /** The block emits light; the page draws a glow sprite. */
        public static final int LIGHT = 1 << 2;
        /** The block has no collision (plants, torches); the page is free to draw it as decoration. */
        public static final int NO_COLLISION = 1 << 3;
        /** The block moved or appeared since the previous pack of the same session (the page flashes it). */
        public static final int CHANGED = 1 << 4;
        /** The cell belongs to no named part. */
        public static final int NO_PART = 1 << 5;
        /** Water or lava: the page draws it semi-transparent and animates it. */
        public static final int LIQUID = 1 << 6;

        private Flags() {
        }
    }
}
