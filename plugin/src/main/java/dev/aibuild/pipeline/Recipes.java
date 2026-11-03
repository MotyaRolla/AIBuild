package dev.aibuild.pipeline;

import dev.aibuild.script.Stdlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the expert recipe cards ({@code stdlib/v<n>/recipes/*.md}) for a request by keywords (Russian and English
 * stems, no model call): one or two cards, most specific first, falling back to the general card. The cards go
 * into the user message so the shared system prompt stays cacheable.
 */
public final class Recipes {
    private Recipes() {
    }

    /** Card name -> keyword stems, checked in this order (more specific subjects first). */
    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("asian", List.of("пагод", "японск", "китайск", "храм", "тории", "сакур", "pagoda", "japan", "chinese",
                "temple", "torii", "shrine", "dojo"));
        KEYS.put("ship", List.of("корабл", "лодк", "судно", "фрегат", "дирижабл", "парусн", "поезд", "паровоз", "машин",
                "автомоб", "танк", "самолет", "самолёт", "ракет", "ship", "boat", "galleon", "airship", "zeppelin", "train",
                "locomotive", "car", "vehicle", "plane", "rocket", "submarine"));
        KEYS.put("creature", List.of("кот", "кош", "собак", "пёс", "пес", "лис", "волк", "медвед", "дракон", "конь", "лошад",
                "птиц", "сова", "орёл", "орел", "рыб", "кит", "черепах", "змея", "змей", "монстр", "чудовищ", "единорог",
                "животн", "зверь", "dragon", "cat", "dog", "fox", "wolf", "bear", "horse", "bird", "owl", "eagle", "fish",
                "whale", "turtle", "snake", "monster", "creature", "unicorn", "animal", "beast", "phoenix", "griffin"));
        KEYS.put("sculpture", List.of("статуя", "статую", "скульптур", "памятник", "монумент", "бюст", "фигур", "рыцар",
                "воин", "statue", "sculpture", "monument", "bust", "figure", "knight", "warrior", "idol"));
        KEYS.put("castle", List.of("замок", "замк", "крепост", "форт", "цитадел", "бастион", "стен", "рвом", "castle",
                "fortress", "fort", "citadel", "keep", "rampart", "stronghold"));
        KEYS.put("tower", List.of("башн", "маяк", "шпил", "колокольн", "tower", "lighthouse", "spire", "belfry", "minaret"));
        KEYS.put("bridge", List.of("мост", "акведук", "виадук", "причал", "пирс", "bridge", "aqueduct", "viaduct", "pier", "dock"));
        KEYS.put("scene", List.of("деревн", "город", "посёл", "посел", "сцен", "пейзаж", "остров", "улиц", "площад", "рынок",
                "village", "town", "city", "scene", "landscape", "island", "street", "square", "market"));
        KEYS.put("nature", List.of("дерев", "лес", "сад", "парк", "роща", "дуб", "ель", "сосн", "ива",
                "tree", "forest", "garden", "park", "grove", "oak", "pine", "willow", "treehouse"));
        KEYS.put("house", List.of("дом", "избу", "изба", "хижин", "коттедж", "таверн", "трактир", "лавк", "магазин", "ферм",
                "мельниц", "кузниц", "особняк", "вилл", "усадьб", "здани", "церк", "часовн", "house", "home", "cottage",
                "cabin", "hut", "tavern", "inn", "shop", "farm", "mill", "smithy", "mansion", "villa", "manor", "building",
                "church", "chapel"));
    }

    /** Names of the 1-2 cards that fit {@code request}, most relevant first. */
    public static List<String> pick(String request) {
        String r = " " + request.toLowerCase(Locale.ROOT) + " ";
        List<String> out = new ArrayList<>();
        int bestPos = Integer.MAX_VALUE;
        for (var e : KEYS.entrySet()) {
            int pos = Integer.MAX_VALUE;
            for (String k : e.getValue()) {
                int i = indexOfWord(r, k);
                if (i >= 0) pos = Math.min(pos, i);
            }
            if (pos == Integer.MAX_VALUE) continue;
            // the subject named first usually is the main subject ("cat on a pumpkin")
            if (pos < bestPos) {
                out.addFirst(e.getKey());
                bestPos = pos;
            } else {
                out.add(e.getKey());
            }
        }
        if (out.isEmpty()) return List.of("general");
        return List.copyOf(out.subList(0, Math.min(2, out.size())));
    }

    /** Stems match at a word start: "кот" matches "кота", not "который"... unless the word itself starts so. */
    private static int indexOfWord(String text, String stem) {
        int from = 0;
        while (true) {
            int i = text.indexOf(stem, from);
            if (i < 0) return -1;
            char before = text.charAt(i - 1);
            if (!Character.isLetter(before) && !excluded(text, i, stem)) return i;
            from = i + 1;
        }
    }

    /** Common words that start like a subject stem but are not one. */
    private static boolean excluded(String text, int i, String stem) {
        String word = text.substring(i, Math.min(text.length(), i + 12)).split("[^\\p{L}]")[0];
        return switch (stem) {
            case "кот" -> word.startsWith("котор") || word.startsWith("котел") || word.startsWith("котёл");
            case "кит" -> !word.equals("кит") && !word.startsWith("кита") && !word.startsWith("киты");
            case "стен" -> word.startsWith("стенд");
            case "дом" -> word.startsWith("домин");
            case "car" -> !word.equals("car") && !word.equals("cars");
            case "лис" -> word.startsWith("лист");
            default -> false;
        };
    }

    /** The recipe text for {@code request} from {@code lib}, or "" when the library has no recipes. */
    public static String forRequest(Stdlib lib, String request) {
        StringBuilder sb = new StringBuilder();
        for (String name : pick(request)) {
            String card = lib.optional("recipes/" + name + ".md");
            if (card != null) sb.append(card.strip()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
