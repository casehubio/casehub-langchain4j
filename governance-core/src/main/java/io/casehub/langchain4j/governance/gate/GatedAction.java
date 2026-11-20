package io.casehub.langchain4j.governance.gate;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares the action type a {@code @Tool} method performs, as the CaseHub risk classifiers know
 * it — for example {@code "account.freeze"}. Without it the tool name is used. Tool names cannot
 * contain dots, so a tool that should match an existing classifier rule needs this annotation.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface GatedAction {

    /** The action type passed to the risk classifiers as {@code PlannedAction.actionType()}. */
    String value();
}
