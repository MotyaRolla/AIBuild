package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.util.Json;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.Severity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReviewDecision {
    static List<String> criteria(String role) {
        return switch (role) {
            case "structure" -> List.of("fidelity", "soundness");
            case "exterior" -> List.of("composition", "coherence", "craft");
            case "interior" -> List.of("usability", "coherence", "craft");
            default -> List.of("fidelity", "soundness", "composition", "coherence", "craft", "usability");
        };
    }

    static List<String> qualityFailures(JsonObject response, String role) {
        List<String> failures = new ArrayList<>();
        JsonObject quality = Json.obj(response, "quality");
        for (String criterion : criteria(role)) {
            JsonObject item = quality == null ? null : Json.obj(quality, criterion);
            int rating = -1;
            try {
                double value=item.get("rating").getAsDouble();
                if (Double.isFinite(value) && value==Math.rint(value)) rating=(int)value;
            } catch (RuntimeException invalid) {
                // An absent or malformed rating cannot satisfy acceptance.
            }
            String evidence = item == null ? "" : Json.str(item, "evidence", "").trim();
            if (rating < 3 || rating > 4 || evidence.length() < 12) {
                failures.add(criterion + ": " + (rating < 0 ? "not assessed" : rating + "/4")
                        + (evidence.length() < 12 ? " (missing observed evidence)" : " - " + evidence));
            }
        }
        return failures;
    }

    static boolean introducesBlocking(AnalysisReport before,AnalysisReport after) {
        for (var issue : after.issues) {
            if (issue.severity()!=Severity.CRITICAL && issue.severity()!=Severity.MAJOR) continue;
            boolean existed=before.issues.stream().anyMatch(old -> old.code().equals(issue.code())
                    && old.severity().rank<=issue.severity().rank);
            if (!existed) return true;
        }
        return after.count(Severity.CRITICAL)>before.count(Severity.CRITICAL)
                || after.count(Severity.MAJOR)>before.count(Severity.MAJOR);
    }

    static boolean hasUnresolvedSerious(JsonObject response, JsonArray verified) {
        Set<String> certified = new HashSet<>();
        for (JsonElement source : Json.arr(response, "checked")) {
            if (!source.isJsonObject()) continue;
            JsonObject original = source.getAsJsonObject();
            if (Json.bool(original, "ok", false) || Json.arr(original, "proof").isEmpty()) continue;
            String id = Json.str(original, "id", "");
            for (JsonElement result : verified) {
                if (result.isJsonObject() && id.equals(Json.str(result.getAsJsonObject(), "id", ""))
                        && Json.bool(result.getAsJsonObject(), "ok", false)) certified.add(id);
            }
        }
        for (JsonElement element : Json.arr(response, "issues")) {
            if (!element.isJsonObject()) continue;
            JsonObject issue = element.getAsJsonObject();
            String severity = Json.str(issue, "severity", "minor").toLowerCase(Locale.ROOT);
            if ((severity.startsWith("crit") || severity.startsWith("maj"))
                    && !certified.contains(Json.str(issue, "check", ""))) return true;
        }
        return false;
    }

    static boolean canFinish(JsonObject response, JsonArray verified, String role, int minScore,
                             int failedOps, int conflicts, boolean checklistComplete, boolean proven) {
        return failedOps == 0 && conflicts == 0 && checklistComplete
                && Json.integer(response, "score", -1) >= minScore
                && qualityFailures(response, role).isEmpty()
                && !hasUnresolvedSerious(response, verified)
                && (Json.bool(response, "done", false) || proven);
    }
}
