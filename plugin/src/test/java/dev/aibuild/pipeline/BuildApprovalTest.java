package dev.aibuild.pipeline;

import com.google.gson.JsonParser;
import dev.aibuild.build.BuildVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildApprovalTest {
    @Test void legacyVersionsRemainUnverifiedAndDraftsSurviveSerialization() {
        BuildVersion legacy=BuildVersion.fromJson(JsonParser.parseString("{\"number\":1,\"score\":95}").getAsJsonObject());
        assertNull(legacy.approved);
        assertFalse(legacy.toJson().has("approved"));
        legacy.approved=false;
        BuildVersion draft=BuildVersion.fromJson(legacy.toJson());
        assertEquals(false,draft.approved);
        assertEquals(false,draft.copy().approved);
        assertTrue(draft.display().contains("[draft]"));
        draft.approved=true;
        assertEquals(true,BuildVersion.fromJson(draft.toJson()).approved);
    }
}
