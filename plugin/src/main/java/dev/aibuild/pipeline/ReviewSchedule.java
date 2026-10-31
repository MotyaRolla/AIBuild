package dev.aibuild.pipeline;

/** A focused repair can schedule a full assessment, but cannot approve the build. */
final class ReviewSchedule {
    private boolean requireFull = true;
    private boolean assessedCurrentModel;
    private int focusedRounds;

    boolean requireFull(int round, int cap) {
        return requireFull || round == cap || focusedRounds >= 2;
    }

    void record(boolean full, int changed, boolean reviewersFinished, boolean requestFull) {
        assessedCurrentModel = full && changed == 0 && reviewersFinished && !requestFull;
        focusedRounds = full ? 0 : focusedRounds + 1;
        requireFull = requestFull || reviewersFinished || !full && changed == 0;
    }

    boolean assessedCurrentModel() {
        return assessedCurrentModel;
    }
}
