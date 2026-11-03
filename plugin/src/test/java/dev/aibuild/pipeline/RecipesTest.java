package dev.aibuild.pipeline;

import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipesTest {
    @Test void picksTheMainSubjectFirst() {
        assertEquals("creature", Recipes.pick("небольшой черный кот с ведьминской шляпой, который сидит на тыковке").getFirst());
        assertEquals(List.of("house"), Recipes.pick("уютный средневековый домик с садом, 14 на 11").subList(0, 1));
        assertEquals("castle", Recipes.pick("небольшой каменный замок с четырьмя башнями и рвом").getFirst());
        assertEquals("tower", Recipes.pick("башня волшебника с винтовой лестницей и куполом").getFirst());
        assertEquals("bridge", Recipes.pick("каменный арочный мост через ручей").getFirst());
        assertEquals("creature", Recipes.pick("дракон, сидящий на скале с расправленными крыльями").getFirst());
        assertEquals("sculpture", Recipes.pick("статуя рыцаря с мечом на пьедестале").getFirst());
        assertEquals("ship", Recipes.pick("парусный корабль с тремя мачтами").getFirst());
        assertEquals("ship", Recipes.pick("стимпанк-дирижабль с гондолой и пропеллерами").getFirst());
        assertEquals("nature", Recipes.pick("огромное древнее дерево с домиком на ветвях").getFirst());
        assertEquals("asian", Recipes.pick("японский сад с прудом, мостиком и пагодой").getFirst());
        assertEquals("scene", Recipes.pick("маленькая деревня из трёх домов вокруг колодца").getFirst());
        assertEquals(List.of("general"), Recipes.pick("что-нибудь красивое"));
        assertFalse(Recipes.pick("дом, который стоит у озера").contains("creature"), "'который' is not a cat");
    }

    @Test void everyBenchPromptGetsASpecificCard() throws IOException {
        for (String line : Files.readAllLines(Path.of("../tools/bench_suite.txt"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            assertNotEquals("general", Recipes.pick(line).getFirst(), line);
            assertFalse(Recipes.forRequest(Stdlib.V2, line).isBlank(), line);
        }
        assertEquals("", Recipes.forRequest(Stdlib.V1, "дом"), "the frozen 2.0 library has no recipes");
    }
}
