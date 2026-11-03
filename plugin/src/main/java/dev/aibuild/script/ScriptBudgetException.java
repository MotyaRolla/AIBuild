package dev.aibuild.script;

/** A script exceeded its instruction, time, memory or cell budget. The message is shown to the model. */
public final class ScriptBudgetException extends RuntimeException {
    public ScriptBudgetException(String message) {
        super(message, null, false, false);
    }
}
