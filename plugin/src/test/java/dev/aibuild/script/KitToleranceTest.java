package dev.aibuild.script;

import dev.aibuild.model.StubBlockInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every kit called the way the model calls them in live builds: fractional numbers straight from rand(), flags
 * (walk: true, shutters: true) where a block is optional, and wood names where a door id is expected.
 */
class KitToleranceTest {
    private static final String CALLS = """
            const q = v => v + 0.37;   // what rand() hands the kits
            const kits = {
              tree: () => ['oak','birch','spruce','pine','dark_oak','cherry','jungle','acacia','willow','azalea','pale_oak','palm','dead']
                  .forEach((s, i) => tree(q(-60 + i * 9), 1, q(-60), {species: s, h: q(6 + i), crown: q(3)})),
              tower: () => { tower(q(0), 1, q(0), q(4), q(14), {palette: palette('castle')}); tower(q(20), 1, q(0), q(3), q(10), {square: true, roof: {type: 'pyramid'}}); },
              curtainWall: () => curtainWall([[q(-30), q(20)], [q(30), q(20)], [q(30), q(40)]], 1, q(9), {palette: palette('castle'), t: 3, walk: true}),
              volume: () => volume(q(-20), q(-50), q(-4), q(-38), 1, {palette: 'medieval', floors: 2, floorH: q(4)}),
              roof: () => roof(q(0), q(-50), q(10), q(-40), q(6), {type: 'hip', height: q(4)}),
              door: () => { box(40, 1, 0, 46, 4, 0, 'stone_bricks'); door(41, 1, 0, 'south', {m: 'spruce'}); door(44, 1, 0, 'south', {m: 'dark_oak_planks', w: 2, frame: 'stone_bricks', step: true, lamp: true}); },
              windowAt: () => { box(40, 1, 10, 46, 6, 10, 'stone_bricks'); windowAt(q(43), q(3), 10, 'south', {w: q(2), h: q(2), shutters: true}); },
              arch: () => arch(q(50), q(0), q(56), q(0), 1, {h: q(5)}),
              column: () => column(q(60), 1, q(0), q(6), {r: q(1)}),
              stairs: () => { stairs(q(60), 1, q(10), 'north', q(4), {w: q(2)}); spiralStair(q(70), 1, q(10), q(10), q(1.5)); },
              battlements: () => battlements(q(80), q(5), q(0), q(90), q(0), 'stone_bricks', {every: q(2)}),
              chimney: () => chimney(q(80), q(10), 1, q(8)),
              balcony: () => balcony(q(80), q(4), q(20), 'south', {w: q(3), d: q(2)}),
              timber: () => timber(q(90), 1, q(20), q(98), q(6), q(20), {every: q(3)}),
              furnish: () => { box(100, 1, 0, 111, 1, 9, 'oak_planks'); box(100, 2, 0, 111, 5, 9, 'stone_bricks', {walls: true}); box(100, 6, 0, 111, 6, 9, 'oak_planks');
                  furnish(q(101), q(1), q(110), q(8), q(1), 'tavern', {wood: 'spruce'}); chandelier(q(105), q(5), q(5), {drop: q(1)}); },
              nature: () => { rock(q(-40), 1, q(60), q(3)); bush(q(-30), 1, q(60), q(2)); pond(q(-20), q(60), q(4), q(3), {depth: q(2)});
                  hill(q(0), q(70), q(6), q(5), q(4)); groundCover(q(-60), q(50), q(20), q(80)); flowers(q(-60), q(50), q(-50), q(60));
                  clear(q(-60), 1, q(50)); path([[q(-10), 0, q(55)], [q(10), 0, q(65)]], {w: q(2)}); lampPost(q(20), 1, q(60), {h: q(4)}); fenceLine([[q(30), q(60)], [q(40), q(66)]]); },
              terrain: () => terrain(q(120), q(60), q(150), q(90), {height: q(6), scale: q(10)}),
              furniture: () => { bed(q(120), 1, q(0), 'north', 'red'); table(q(124), 1, q(0), {w: q(2), d: q(2)}); chair(q(128), 1, q(0), 'west');
                  shelves(q(130), 1, q(0), q(134), q(0), q(3)); fireplace(q(137), 1, q(0), 'south'); lamp(q(140), 1, q(0)); rug(q(120), q(4), q(126), q(8), 1, 'red', 'white');
                  kitchen(q(130), 1, q(6), 'south', q(3), 'north'); }
              ,
              liveMistakes: () => { box(160, 1, 0, 166, 4, 0, 'stone'); banner(162, 3, 1, 'south', 'blue'); banner(163, 3, 1, 'south', 'red_banner');
                  box(160, 9, 2, 166, 9, 8, 'oak_planks'); chandelier(163, 8, 5, {drop: 2, r: 2, m: 'dark_oak_fence'});
                  furnish(161, 2, 165, 7, 0, 'throne', {wood: 'spruce_planks', stone: mix({stone_bricks: 2, andesite: 1})});
                  volume(170, 0, 184, 12, 1, {palette: palette('castle'), floors: 2, wood: 'spruce_planks', hearth: true, rooms: [['throne'], ['bedroom']]}); }
            };
            for (const k in kits) { try { kits[k](); } catch (e) { log('FAIL ' + k + ': ' + e.message); } }
            log('done');
            """;

    @Test void kitsTakeFractionalNumbersFlagsAndWoodNames() {
        ScriptResult r = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2).run(CALLS, 7, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        assertEquals(java.util.List.of("done"), r.logs(), "kits that failed");
    }
}
