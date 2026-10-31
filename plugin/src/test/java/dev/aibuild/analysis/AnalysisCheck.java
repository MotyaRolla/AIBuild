package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Self-checking test for the analysis package (plain main, no JUnit). Prints PASS/FAIL per check and exits with
 * status 1 when anything failed.
 *
 * <p>Run with:</p>
 * <pre>
 * java -Djava.awt.headless=true -cp /root/ai-build/.build/agentC:$(cat cp.txt) \
 *      dev.aibuild.analysis.AnalysisCheck
 * </pre>
 */
public final class AnalysisCheck {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int checks;

    /** 7x7 house: cobblestone floor, oak walls y1..4, door on south, slab roof, pane window, torches. */
    private static final int X0 = 0, Z0 = 0, X1 = 6, Z1 = 6;
    private static final int WALL_TOP = 4;
    private static final int ROOF_Y = 5;

    private AnalysisCheck() {
    }

    public static void main(String[] args) throws Exception {
        BlockInfo info = new StubBlockInfo();
        File outDir = java.nio.file.Files.createTempDirectory("aibuild-analysis-").toFile();
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();

        System.out.println("=== AIBuild analysis self-check ===");

        VoxelModel house = buildHouse(info);
        AnalysisReport base = StructureAnalyzer.analyze(house, info, null);
        System.out.println("---- BASELINE REPORT ----");
        System.out.println(base.toText());

        // ------------------------------------------------------------------ baseline expectations
        check("baseline has one room", base.rooms == 1, "rooms=" + base.rooms);
        check("baseline finds interior volume", base.interiorVolume >= 80 && base.interiorVolume <= 100,
                "interiorVolume=" + base.interiorVolume);
        check("baseline counts exactly one door", base.doors == 1, "doors=" + base.doors);
        check("baseline counts the window", base.windows == 1, "windows=" + base.windows);
        check("baseline has no floating blocks", !base.hasCode("floating_blocks"),
                "issues=" + codes(base));
        check("baseline has no blocking issues", !base.hasBlocking(), "issues=" + codes(base));
        check("baseline has no dark interior (torches present)", !base.hasCode("dark_interior"),
                "issues=" + codes(base));
        check("baseline has no unsupported attachments", !base.hasCode("unsupported_attachment"),
                "issues=" + codes(base));
        check("baseline has no door_blocked", !base.hasCode("door_blocked"), "issues=" + codes(base));
        check("baseline light sources counted", base.lightSources >= 2, "lights=" + base.lightSources);
        check("baseline metrics contain detailScore", base.metrics.containsKey("detailScore"),
                "keys=" + base.metrics.keySet());
        check("baseline metrics contain facade south", base.metrics.containsKey("facade_south_visible"),
                "keys=" + base.metrics.keySet());
        check("text under 4500 chars", base.toText().length() <= AnalysisReport.TEXT_LIMIT,
                "len=" + base.toText().length());

        // ------------------------------------------------------------------ (1) remove the roof
        VoxelModel noRoof = buildHouseWithoutRoof(info);
        AnalysisReport r1 = StructureAnalyzer.analyze(noRoof, info, null);
        boolean roofReported = r1.hasCode("no_enclosed_interior") || r1.hasCode("sky_exposed_interior");
        check("(1) roof removal is detected", roofReported, "issues=" + codes(r1) + " interior=" + r1.interiorVolume);
        System.out.println("   roof-removal issues: " + codes(r1));
        System.out.println("   interior=" + r1.interiorVolume + " rooms=" + r1.rooms
                + " skyExposed=" + r1.metricInt("skyExposedInterior", -1));

        // ------------------------------------------------------------------ (2) floating stone at y=12
        VoxelModel floating = house.copy();
        floating.set(3, 12, 3, info.parse("stone"));
        AnalysisReport r2 = StructureAnalyzer.analyze(floating, info, null);
        check("(2) floating block at y=12 detected", r2.hasCode("floating_blocks"), "issues=" + codes(r2));
        Issue floatIssue = first(r2, "floating_blocks");
        check("(2) floating issue is MAJOR", floatIssue != null && floatIssue.severity() == Severity.MAJOR,
                floatIssue == null ? "no issue" : floatIssue.severity().toString());
        check("(2) floating issue has a sample", floatIssue != null && floatIssue.sampleCount() > 0,
                floatIssue == null ? "no issue" : "samples=" + floatIssue.sampleCount());
        check("(2) floating line mentions coordinates", floatIssue != null
                        && floatIssue.toLine().contains("(3,12,3)"),
                floatIssue == null ? "no issue" : floatIssue.toLine());
        check("(2) floating still has 1 room", r2.rooms == 1, "rooms=" + r2.rooms);
        System.out.println("   " + (floatIssue == null ? "(none)" : floatIssue.toLine()));

        // ------------------------------------------------------------------ (3) no torches => dark
        VoxelModel dark = buildHouse(info, false);
        AnalysisReport r3 = StructureAnalyzer.analyze(dark, info, null);
        check("(3) removing torches reports dark_interior", r3.hasCode("dark_interior"), "issues=" + codes(r3));
        check("(3) darkSpots > 0", r3.darkSpots > 0, "darkSpots=" + r3.darkSpots);
        Issue darkIssue = first(r3, "dark_interior");
        check("(3) dark issue is MAJOR", darkIssue != null && darkIssue.severity() == Severity.MAJOR,
                darkIssue == null ? "no issue" : darkIssue.severity().toString());
        System.out.println("   dark spots=" + r3.darkSpots + " avgLight="
                + r3.metricNum("avgInteriorLight", -1));

        // ------------------------------------------------------------------ (4) wall torch without support
        VoxelModel badTorch = house.copy();
        // wall torch on the north wall, facing SOUTH => support must be at pos - facing = north of it (y,z-1)
        // put it in mid-air instead: 2 blocks inside the room, facing north => support at z-1 is air
        badTorch.set(3, 2, 3, info.parse("wall_torch[facing=north]"));
        AnalysisReport r4 = StructureAnalyzer.analyze(badTorch, info, null);
        check("(4) unsupported wall torch detected", r4.hasCode("unsupported_attachment"), "issues=" + codes(r4));
        Issue torchIssue = first(r4, "unsupported_attachment");
        check("(4) attachment issue is MAJOR", torchIssue != null && torchIssue.severity() == Severity.MAJOR,
                torchIssue == null ? "no issue" : torchIssue.severity().toString());
        System.out.println("   " + (torchIssue == null ? "(none)" : torchIssue.toLine()));

        // A torch WITH support must not be reported: put it on the interior face of the south wall.
        VoxelModel goodTorch = house.copy();
        goodTorch.set(4, 3, Z1 - 1, info.parse("wall_torch[facing=north]"));
        AnalysisReport r4b = StructureAnalyzer.analyze(goodTorch, info, null);
        check("(4b) supported wall torch is accepted", !r4b.hasCode("unsupported_attachment"),
                "issues=" + codes(r4b));

        // ------------------------------------------------------------------ floating: nothing seeded
        VoxelModel skyOnly = new VoxelModel();
        skyOnly.set(0, 10, 0, info.parse("stone"));
        skyOnly.set(1, 10, 0, info.parse("stone"));
        AnalysisReport r5 = StructureAnalyzer.analyze(skyOnly, info, null);
        check("floating-only model is reported", r5.hasCode("floating_blocks"), "issues=" + codes(r5));

        // ------------------------------------------------------------------ terrain / unset handling
        VoxelModel groundOnly = new VoxelModel();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) groundOnly.set(x, 0, z, info.parse("stone"));
        AnalysisReport r6 = StructureAnalyzer.analyze(groundOnly, info, null);
        check("terrain floor is not floating", !r6.hasCode("floating_blocks"), "issues=" + codes(r6));

        // ------------------------------------------------------------------ empty model
        AnalysisReport rEmpty = StructureAnalyzer.analyze(new VoxelModel(), info, null);
        check("empty model is CRITICAL", rEmpty.hasCode("empty") && rEmpty.count(Severity.CRITICAL) == 1,
                "issues=" + codes(rEmpty));
        check("empty has no bounds", rEmpty.bounds == null, "bounds=" + java.util.Arrays.toString(rEmpty.bounds));
        check("empty hasBlocking", rEmpty.hasBlocking(), "hasBlocking=false");
        check("empty text is short", rEmpty.toText().length() < 200, "len=" + rEmpty.toText().length());

        // ------------------------------------------------------------------ options: footprint
        AnalysisReport fpOk = StructureAnalyzer.analyze(house, info, new AnalyzerOptions().expectedFootprint(7, 7));
        check("expected footprint 7x7 matches", !fpOk.hasCode("footprint_mismatch"), "issues=" + codes(fpOk));
        AnalysisReport fpBad = StructureAnalyzer.analyze(house, info, new AnalyzerOptions().expectedFootprint(12, 12));
        check("expected footprint 12x12 mismatches", fpBad.hasCode("footprint_mismatch"), "issues=" + codes(fpBad));
        AnalysisReport noInterior = StructureAnalyzer.analyze(noRoof, info,
                new AnalyzerOptions().expectInterior(false));
        check("expectInterior=false suppresses no_enclosed_interior",
                !noInterior.hasCode("no_enclosed_interior"), "issues=" + codes(noInterior));

        // ------------------------------------------------------------------ gravity
        VoxelModel sand = house.copy();
        sand.set(1, 3, 1, info.parse("sand"));
        AnalysisReport rGravity = StructureAnalyzer.analyze(sand, info, null);
        check("unsupported sand is reported", rGravity.hasCode("unsupported_gravity"), "issues=" + codes(rGravity));

        // ------------------------------------------------------------------ liquids
        VoxelModel water = house.copy();
        water.set(3, 1, 3, info.parse("water"));
        AnalysisReport rWater = StructureAnalyzer.analyze(water, info, null);
        check("exposed water is reported", rWater.hasCode("liquid_may_flow"), "issues=" + codes(rWater));

        // ------------------------------------------------------------------ beds
        VoxelModel bed = house.copy();
        bed.set(5, 1, 5, info.parse("red_bed[facing=south]"));
        AnalysisReport rBed = StructureAnalyzer.analyze(bed, info, null);
        check("incomplete bed is reported", rBed.hasCode("bed_incomplete"), "issues=" + codes(rBed));

        // ------------------------------------------------------------------ vertical access
        VoxelModel tower = buildTower(info);
        AnalysisReport rTower = StructureAnalyzer.analyze(tower, info, null);
        check("multi-storey tower without stairs reports no_vertical_access",
                rTower.hasCode("no_vertical_access"), "issues=" + codes(rTower));
        VoxelModel towerOk = tower.copy();
        for (int y = 1; y <= 6; y++) towerOk.set(2, y, 0, info.parse("ladder[facing=south]"));
        AnalysisReport rTowerOk = StructureAnalyzer.analyze(towerOk, info, null);
        check("tower with a ladder does not report no_vertical_access",
                !rTowerOk.hasCode("no_vertical_access"), "issues=" + codes(rTowerOk));

        // ------------------------------------------------------------------ non-square / irregular shapes
        // (regression: facade bookkeeping must not assume a square footprint)
        for (int[] dims : new int[][]{{11, 5, 4}, {4, 5, 11}, {9, 3, 13}, {3, 3, 3}, {1, 6, 1}}) {
            VoxelModel box = hollowBox(info, dims[0], dims[1], dims[2]);
            AnalysisReport rBox = StructureAnalyzer.analyze(box, info, null);
            // tiny boxes legitimately have no room (one interior cell / none at all) - only "no crash" matters
            boolean roomOk = dims[0] >= 5 && dims[2] >= 5 ? rBox.rooms == 1 : rBox.rooms >= 0;
            check("(" + dims[0] + "x" + dims[1] + "x" + dims[2] + ") analysed without exception",
                    roomOk, "rooms=" + rBox.rooms + " issues=" + codes(rBox));
            check("(" + dims[0] + "x" + dims[1] + "x" + dims[2] + ") text has no NaN",
                    !rBox.toText().contains("NaN"), "NaN in text");
            for (String side : new String[]{"south", "north", "east", "west"}) {
                int vis = rBox.metricInt("facade_" + side + "_visible", -1);
                check("(" + dims[0] + "x" + dims[1] + "x" + dims[2] + ") facade " + side + " measured",
                        vis > 0 && rBox.metrics.containsKey("facade_" + side + "_relief"), "visible=" + vis);
            }
        }

        // ------------------------------------------------------------------ Issue API
        Issue issue = new Issue(Severity.MAJOR, "floating_blocks",
                "12 blocks not connected to the ground (component of 12 at x3..5 y7..8 z2..4)",
                List.of(new int[]{3, 7, 2}, new int[]{4, 7, 2}));
        String line = issue.toLine();
        check("Issue.toLine format", line.equals("MAJOR floating_blocks: 12 blocks not connected to the ground"
                        + " (component of 12 at x3..5 y7..8 z2..4) e.g. (3,7,2) (4,7,2)"), line);
        Issue clamped = new Issue(Severity.MINOR, "x", "y",
                List.of(new int[]{1, 1, 1}, new int[]{2, 2, 2}, new int[]{3, 3, 3}, new int[]{4, 4, 4},
                        new int[]{5, 5, 5}, new int[]{6, 6, 6}, new int[]{7, 7, 7}, new int[]{8, 8, 8},
                        new int[]{9, 9, 9}, new int[]{10, 10, 10}));
        check("Issue clamps samples to 8", clamped.sampleCount() == 8, "n=" + clamped.sampleCount());
        check("Issue rejects null samples", new Issue(Severity.INFO, "a", "b", null).sampleCount() == 0, "n>0");
        check("Severity.parse", Severity.parse("major") == Severity.MAJOR
                && Severity.parse("CRITICAL") == Severity.CRITICAL && Severity.parse("nope") == null, "parse");
        check("severity ordering in report", sortedBySeverity(base), "order=" + base.issues);

        // ------------------------------------------------------------------ AnalysisReport helpers
        check("count(MAJOR) matches hasBlocking",
                (base.count(Severity.MAJOR) + base.count(Severity.CRITICAL) > 0) == base.hasBlocking(), "count");
        check("topBlocks bounded", base.topBlocks.size() <= AnalysisReport.TOP_BLOCK_LIMIT,
                "n=" + base.topBlocks.size());
        check("topBlocks sorted descending", sortedDesc(base.topBlocks), "top=" + base.topBlocks);
        check("distinctBlocks == name count", base.distinctBlocks == 7,
                "distinct=" + base.distinctBlocks + " names=" + names(base));

        // ------------------------------------------------------------------ text renderer
        String text = TextRenderer.render(house, info, new TextRenderer.Options().title("7x7 oak cottage"));
        check("render has header", text.contains("AIBUILD RENDER"), "no header");
        check("render states coordinates", text.contains("front faces SOUTH"), "no convention line");
        check("render has legend", text.contains("LEGEND"), "no legend");
        check("render has layers", text.contains("LAYER y=0") && text.contains("LAYER y=5"), "no layers");
        check("render has elevations", text.contains("VIEW FROM SOUTH") && text.contains("VIEW FROM WEST"),
                "no elevations");
        check("render has top view", text.contains("TOP VIEW") && text.contains("HEIGHT MAP"), "no top view");
        check("render legend lists oak_planks", text.contains("oak_planks"), "no planks in legend");
        check("render legend lists the door", text.contains("oak_door"), "no door in legend");
        check("render legend lists glass_pane", text.contains("glass_pane"), "no pane in legend");
        check("render row format", hasRowFormat(text), "no 'z=  n |...|' rows");
        check("render no trailing spaces", noTrailingSpaces(text), "trailing spaces found");
        check("render always uses 2-char semantic codes", text.contains("symbols=2char") && text.contains("2nd char = orientation"), "no semantic codes");
        String layersOnly = TextRenderer.render(house, info, TextRenderer.Options.layersOnly());
        check("layersOnly omits elevations", !layersOnly.contains("VIEW FROM") && layersOnly.contains("LAYER"),
                "sections wrong");
        String cropped = TextRenderer.render(house, info, new TextRenderer.Options().region(
                new int[]{2, 1, 2, 4, 3, 4}));
        check("crop renders the sub-region", cropped.contains("x2..4") && cropped.contains("y1..3"),
                "crop header wrong: " + firstLine(cropped));
        check("render is deterministic", text.equals(TextRenderer.render(house, info,
                new TextRenderer.Options().title("7x7 oak cottage"))), "two renders differ");
        check("render empty model", TextRenderer.render(new VoxelModel(), info, null).contains("empty"), "no empty");
        check("2-char fallback for big palettes", twoCharFallback(info), "no 2-char");

        System.out.println("---- TEXT RENDER ----");
        System.out.println(text);
        System.out.println("---- END RENDER (" + text.length() + " chars) ----");

        // ------------------------------------------------------------------ layer content spot checks
        String layerY1 = layerBlock(text, 1);
        check("layer y=1 shows the door symbol", layerY1.contains(doorSymbol(text)), "layer y=1=" + layerY1);
        check("layer y=2 shows the upper door symbol", layerBlock(text, 2).contains(doorSymbol(text)),
                "layer y=2=" + layerBlock(text, 2));
        check("layer y=0 uses terrain/floor symbols only", !layerBlock(text, 0).contains("."),
                "layer y=0=" + layerBlock(text, 0));
        check("layer y=5 is the roof", layerBlock(text, 5).indexOf('.') < 0, "layer y=5=" + layerBlock(text, 5));

        // region clamping: a crop larger than the model is clamped, not rejected
        String huge = TextRenderer.render(house, info, new TextRenderer.Options()
                .region(new int[]{-100, -100, -100, 100, 100, 100}));
        check("oversized crop is clamped to the model", huge.contains("x0..6") && huge.contains("LAYER y=0"),
                "header=" + firstLine(huge));
        // a 1x1x1 crop still renders one layer
        String tiny = TextRenderer.render(house, info, new TextRenderer.Options()
                .region(new int[]{3, 1, 6, 3, 1, 6}));
        check("1x1x1 crop renders", tiny.contains("LAYER y=1") && tiny.length() < 2500, "len=" + tiny.length());

        // ------------------------------------------------------------------ blocking semantics
        AnalysisReport badHouse = StructureAnalyzer.analyze(floating, info, null);
        check("floating model is blocking", badHouse.hasBlocking(), "hasBlocking=false");
        AnalysisReport minorOnly = StructureAnalyzer.analyze(house, info, null);
        check("clean house is not blocking", !minorOnly.hasBlocking(), "issues=" + codes(minorOnly));
        check("count(CRITICAL)==0 for the house", minorOnly.count(Severity.CRITICAL) == 0,
                "critical=" + minorOnly.count(Severity.CRITICAL));
        check("severityCounts sums to issues", countSum(minorOnly) == minorOnly.issues.size(),
                "sum=" + countSum(minorOnly) + " total=" + minorOnly.issues.size());

        // ------------------------------------------------------------------ metric/issue helpers
        check("hasCode is case-insensitive", minorOnly.hasCode("FLAT_FACADE_SOUTH")
                || minorOnly.hasCode("NO_ROOF_OVERHANG"), "hasCode failed");
        check("issues(Severity) helper", minorOnly.issues(Severity.MINOR).size() == minorOnly.count(Severity.MINOR),
                "mismatch");
        check("metricInt fallback", minorOnly.metricInt("nope", -7) == -7, "fallback");
        check("facadeMetrics helper", minorOnly.facadeMetrics("south").containsKey("visible"),
                "keys=" + minorOnly.facadeMetrics("south"));

        // ------------------------------------------------------------------ iso renderer
        BufferedImage img = IsoRenderer.render(house, info, 0, 10);
        check("iso image has plausible size", img.getWidth() > 60 && img.getHeight() > 60,
                img.getWidth() + "x" + img.getHeight());
        check("iso image is ARGB", img.getType() == BufferedImage.TYPE_INT_ARGB, "type=" + img.getType());
        check("iso image has non-background pixels", countOpaque(img) > 200, "opaque=" + countOpaque(img));
        BufferedImage sheet = IsoRenderer.renderSheet(house, info, 8);
        File png = new File(outDir, "house.png");
        IsoRenderer.writePng(sheet, png);
        check("house.png exists", png.isFile(), "missing");
        check("house.png > 1KB", png.isFile() && png.length() > 1024, "size=" + png.length());
        BufferedImage reload = ImageIO.read(png);
        check("house.png decodes", reload != null && reload.getWidth() == sheet.getWidth(),
                reload == null ? "null" : reload.getWidth() + "x" + reload.getHeight());
        BufferedImage side = IsoRenderer.render(house, info, 2, 12);
        check("iso view 2 differs from view 0", !sameImage(img, side), "identical images");
        System.out.println("   wrote " + png.getAbsolutePath() + " (" + png.length() + " bytes, "
                + sheet.getWidth() + "x" + sheet.getHeight() + ")");

        // ------------------------------------------------------------------ performance: 64x32x64 shell
        VoxelModel big = buildShell(info, 64, 32, 64);
        long t0 = System.nanoTime();
        AnalysisReport rBig = StructureAnalyzer.analyze(big, info, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("---- PERFORMANCE ----");
        System.out.println("   shell " + big.size() + " cells (" + big.nonAirCount() + " non-air) analysed in "
                + ms + " ms; rooms=" + rBig.rooms + " interior=" + rBig.interiorVolume + " dark=" + rBig.darkSpots);
        check("big shell analysed in < 1500 ms", ms < 1500, ms + " ms");
        check("big shell has an interior", rBig.interiorVolume > 1000, "interior=" + rBig.interiorVolume);
        check("big shell has no floating blocks", !rBig.hasCode("floating_blocks"), "issues=" + codes(rBig));
        check("big shell report text is bounded", rBig.toText().length() <= AnalysisReport.TEXT_LIMIT,
                "len=" + rBig.toText().length());

        // a second, larger pass to make sure there is no pathological blow-up
        long t1 = System.nanoTime();
        AnalysisReport rBig2 = StructureAnalyzer.analyze(big, info, new AnalyzerOptions().expectInterior(true));
        long ms2 = (System.nanoTime() - t1) / 1_000_000L;
        check("repeat analysis stays fast (< 1500 ms)", ms2 < 1500, ms2 + " ms");
        check("repeat analysis is stable", rBig2.rooms == rBig.rooms && rBig2.nonAir == rBig.nonAir, "unstable");

        // ------------------------------------------------------------------ thread safety smoke test
        boolean concurrentOk = runConcurrent(house, info);
        check("concurrent analysis is stable", concurrentOk, "concurrent results differ");

        // ------------------------------------------------------------------ summary
        System.out.println();
        if (FAILURES.isEmpty()) {
            System.out.println("RESULT: PASS (" + checks + " checks)");
        } else {
            System.out.println("RESULT: FAIL (" + FAILURES.size() + " of " + checks + " checks failed)");
            for (String f : FAILURES) System.out.println("  FAILED: " + f);
            System.out.flush();
            System.exit(1);
        }
    }

    // =====================================================================================================
    // Fixtures
    // =====================================================================================================

    /** 7x7 house with a cobblestone floor, oak walls y1..4, door and window on the south wall, flat slab roof. */
    private static VoxelModel buildHouse(BlockInfo info) {
        return buildHouse(info, true);
    }

    private static VoxelModel buildHouse(BlockInfo info, boolean torches) {
        BlockSpec cobble = info.parse("cobblestone");
        BlockSpec planks = info.parse("oak_planks");
        BlockSpec slab = info.parse("oak_slab[type=bottom]");
        BlockSpec pane = info.parse("glass_pane");
        BlockSpec doorLower = info.parse("oak_door[facing=south,half=lower,hinge=left]");
        BlockSpec doorUpper = info.parse("oak_door[facing=south,half=upper,hinge=left]");
        BlockSpec torch = info.parse("torch");
        BlockSpec wallTorch = info.parse("wall_torch[facing=south]");

        VoxelModel m = new VoxelModel();
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                m.set(x, 0, z, cobble);                       // floor (replaces terrain)
                for (int y = 1; y <= WALL_TOP; y++) {
                    boolean edge = x == X0 || x == X1 || z == Z0 || z == Z1;
                    if (edge) m.set(x, y, z, planks);         // walls
                }
                m.set(x, ROOF_Y, z, slab);                    // flat roof
            }
        }
        // door on the south wall (z = Z1), x = 3, two blocks high
        m.set(3, 1, Z1, doorLower);
        m.set(3, 2, Z1, doorUpper);
        m.set(3, ROOF_Y, Z1, slab);                           // keep the lintel closed

        // window: one glass pane in the south wall at y=3, x=1
        m.set(1, 3, Z1, pane);
        // interior torches
        if (torches) {
            m.set(1, 1, 1, torch);
            m.set(5, 1, 5, torch);
            m.set(5, 1, 1, torch);
            m.set(1, 1, 5, torch);
            // wall torch on the interior face of the north wall: facing points AWAY from the support, so
            // facing=south => support = pos - facing = the wall at z0
            m.set(3, 3, Z0 + 1, wallTorch);
        }
        return m;
    }

    private static VoxelModel buildHouseWithoutRoof(BlockInfo info) {
        VoxelModel m = buildHouse(info);
        for (int x = X0; x <= X1; x++) {
            for (int z = Z0; z <= Z1; z++) {
                m.set(x, ROOF_Y, z, null);
            }
        }
        return m;
    }

    /** A 2-storey shell (4x4 interior, 3 blocks per storey) to exercise vertical access. */
    private static VoxelModel buildTower(BlockInfo info) {
        BlockSpec planks = info.parse("oak_planks");
        BlockSpec cobble = info.parse("cobblestone");
        BlockSpec slab = info.parse("oak_slab[type=bottom]");
        VoxelModel m = new VoxelModel();
        int x0 = 0, x1 = 5, z0 = 0, z1 = 5, top = 7;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                m.set(x, 0, z, cobble);
                m.set(x, top + 1, z, slab);
                for (int y = 1; y <= top; y++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (edge) m.set(x, y, z, planks);
                }
            }
        }
        // floor between storeys
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = z0 + 1; z < z1; z++) m.set(x, 3, z, slab);
        }
        // door
        m.set(2, 1, z1, info.parse("oak_door[facing=south,half=lower]"));
        m.set(2, 2, z1, info.parse("oak_door[facing=south,half=upper]"));
        m.set(2, 1, z0, info.parse("wall_torch[facing=south]"));
        return m;
    }

    /** Hollow box with 1-thick walls/floor/roof and no openings; exercises arbitrary dimensions. */
    private static VoxelModel hollowBox(BlockInfo info, int sx, int sy, int sz) {
        BlockSpec wall = info.parse("stone_bricks");
        BlockSpec floor = info.parse("stone");
        VoxelModel m = new VoxelModel();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int y = 0; y <= sy; y++) {
                    boolean shell = y == 0 || y == sy || x == 0 || x == sx - 1 || z == 0 || z == sz - 1;
                    if (shell) m.set(x, y, z, y == 0 ? floor : wall);
                }
            }
        }
        return m;
    }

    /** Large hollow shell: 1-2 block thick walls, no openings, with a flat slab roof. */
    private static VoxelModel buildShell(BlockInfo info, int sx, int sy, int sz) {
        BlockSpec wall = info.parse("stone_bricks");
        BlockSpec floor = info.parse("stone");
        BlockSpec roof = info.parse("oak_slab[type=bottom]");
        BlockSpec torch = info.parse("torch");
        VoxelModel m = new VoxelModel();
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                m.set(x, 0, z, floor);
                m.set(x, sy, z, roof);
                for (int y = 1; y < sy; y++) {
                    boolean edge = x == 0 || x == sx - 1 || z == 0 || z == sz - 1;
                    if (edge) m.set(x, y, z, wall);
                }
            }
        }
        // a few torches inside so lighting does not make everything dark
        for (int x = 4; x < sx - 4; x += 8) {
            for (int z = 4; z < sz - 4; z += 8) m.set(x, 1, z, torch);
        }
        return m;
    }

    private static boolean twoCharFallback(BlockInfo info) {
        VoxelModel m = new VoxelModel();
        int y = 1;
        for (int i = 0; i < 100; i++) {
            BlockSpec s = info.parse("stone" + i);
            m.set(i % 10, y, i / 10, s);
        }
        String out = TextRenderer.render(m, info, TextRenderer.Options.layersOnly());
        return out.contains("symbols=2char");
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name + " [" + detail + "]");
            FAILURES.add(name + " [" + detail + "]");
        }
    }

    private static String codes(AnalysisReport r) {
        StringBuilder sb = new StringBuilder();
        for (Issue i : r.issues) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i.severity().id()).append(':').append(i.code());
        }
        return sb.toString();
    }

    private static Issue first(AnalysisReport r, String code) {
        for (Issue i : r.issues) if (i.code().equals(code)) return i;
        return null;
    }

    private static int countSum(AnalysisReport r) {
        int n = 0;
        for (Severity sev : Severity.values()) n += r.count(sev);
        return n;
    }

    private static boolean sortedBySeverity(AnalysisReport r) {
        int last = -1;
        for (Issue i : r.issues) {
            if (i.severity().rank < last) return false;
            last = i.severity().rank;
        }
        return true;
    }

    private static boolean sortedDesc(List<Map.Entry<String, Integer>> top) {
        for (int i = 1; i < top.size(); i++) {
            if (top.get(i - 1).getValue() < top.get(i).getValue()) return false;
        }
        return true;
    }

    private static String names(AnalysisReport r) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : r.topBlocks) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey());
        }
        return sb.toString();
    }

    /** The rows of LAYER y=n (between the "LAYER y=n" line and the next section). */
    private static String layerBlock(String text, int y) {
        String marker = "LAYER y=" + y + "\n";
        int i = text.indexOf(marker);
        if (i < 0) return "";
        int j = i + marker.length();
        int end = text.indexOf("LAYER y=", j);
        if (end < 0) end = text.length();
        int nextSection = text.indexOf("ELEVATION", j);
        if (nextSection >= 0 && nextSection < end) end = nextSection;
        return text.substring(j, end);
    }

    /** The legend symbol assigned to the door lower half (the entry without {@code half=upper}). */
    private static String doorSymbol(String text) {
        String fallback = "?";
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.contains(" = ") || !t.contains("oak_door")) continue;
            int sp = t.indexOf(" = ");
            String sym = t.substring(0, sp).trim();
            int lastSpace = sym.lastIndexOf(' ');
            if (lastSpace >= 0) sym = sym.substring(lastSpace + 1);
            if (t.contains("half=upper")) {
                fallback = sym;
                continue;
            }
            return sym; // lower half: this is the layer y=1 door
        }
        return fallback;
    }

    private static boolean hasRowFormat(String text) {
        for (String line : text.split("\n", -1)) {
            if (line.matches("z=\\s*-?\\d+ \\|.*\\|")) return true;
        }
        return false;
    }

    private static boolean noTrailingSpaces(String text) {
        for (String line : text.split("\n", -1)) {
            if (line.length() > 1 && line.charAt(line.length() - 1) == ' ') return false;
        }
        return true;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static int countOpaque(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                // background is a very light gradient; block pixels are darker / saturated
                if (a > 200 && (r + g + b) < 700) n++;
            }
        }
        return n;
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y += 3) {
            for (int x = 0; x < a.getWidth(); x += 3) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    private static boolean runConcurrent(VoxelModel model, BlockInfo info) {
        final int threads = 4;
        final String[] results = new String[threads];
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                try {
                    results[idx] = StructureAnalyzer.analyze(model, info, null).toText();
                } catch (RuntimeException e) {
                    results[idx] = "EX:" + e;
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            try {
                t.join(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        for (int i = 1; i < threads; i++) {
            if (results[i] == null || !results[i].equals(results[0])) return false;
        }
        return true;
    }

    static {
        Locale.setDefault(Locale.ROOT);
    }
}
