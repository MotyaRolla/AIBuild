package dev.aibuild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat link a player clicks to open a build.
 *
 * <p>The first version wrote {@code <click:open_url:'<v0>'>} into the template. MiniMessage substitutes
 * placeholders in the message body but not inside a tag argument, so the quoted placeholder stayed literal and
 * players saw {@code <click:open_url:'<v0>'>} as text with nothing to click. These tests assert on the parsed
 * component, which is what the client actually receives.</p>
 */
class MsgLinkTest {

    private static final String URL = "http://127.0.0.1:35105/live/583ef62bf634efb7b9f462d313be0055/";

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Collects click events anywhere in the tree, since the link may be a child rather than the root. */
    private static ClickEvent findClick(Component c) {
        if (c.clickEvent() != null) return c.clickEvent();
        for (Component child : c.children()) {
            ClickEvent found = findClick(child);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void theLinkIsClickableAndOpensTheUrl() {
        ClickEvent click = findClick(Msg.link("<gray>Смотреть: </gray>", URL));
        assertNotNull(click, "a click event must be attached, or the link is just text");
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
    }

    @Test
    void noRawMarkupLeaksIntoTheMessage() {
        String text = plain(Msg.link("<gray>Смотреть в браузере: </gray>", URL));
        assertFalse(text.contains("<click"), "the player must never see the tag itself: " + text);
        assertFalse(text.contains("<v0>"), "the placeholder must be gone: " + text);
        assertTrue(text.contains(URL), "the address is shown: " + text);
    }

    @Test
    void theSentenceAroundTheLinkSurvives() {
        String text = plain(Msg.link("<gray>Смотреть: </gray>", URL)
                .append(Msg.mm("<gray> (ссылка только для этой постройки)</gray>")));
        assertTrue(text.startsWith("Смотреть: "), text);
        assertTrue(text.contains(URL), text);
        assertTrue(text.endsWith("(ссылка только для этой постройки)"), text);
    }

    @Test
    void aMalformedUrlCannotInjectMarkup() {
        // The URL comes from config, so it is not trusted to be free of MiniMessage tags. It must be shown
        // verbatim, never parsed as markup, and an address the client cannot open must not throw.
        String evil = "http://x/<red>boom";
        Component c = Msg.link("<gray>Смотреть: </gray>", evil);
        assertTrue(plain(c).contains(evil), "the address is shown verbatim, not interpreted: " + plain(c));
        assertEquals(null, findClick(c), "an unopenable address degrades to text instead of throwing");
    }

    @Test
    void anAddressWithASpaceDoesNotBreakTheAnnouncement() {
        assertEquals(null, findClick(Msg.link("<gray>x: </gray>", "http://host/a b")),
                "openUrl rejects spaces; the sentence must still be sent");
    }

    @Test
    void noUrlMeansNoLinkRatherThanAnEmptyOne() {
        Component c = Msg.link("<gray>Смотреть: </gray>", "");
        assertEquals(null, findClick(c), "an empty url must not produce a click event");
        assertEquals("Смотреть: ", plain(c));
    }
}
