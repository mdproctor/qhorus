package io.casehub.qhorus.examples.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Unstructured worker agent for Zone 1 of the normative benchmark.
 *
 * <p>
 * Deliberately teaches no Qhorus vocabulary (DONE/FAILURE/DECLINE/STATUS).
 * Responds with either {@code COMPLETED: <explanation>} or
 * {@code CANNOT_COMPLETE: <explanation>} — allowing prefix-based classification
 * with no LLM judge required.
 *
 * <p>
 * This is the control group: unstructured channel, no commitment lifecycle,
 * no ledger recording. Used only in Zone1UnstructuredBaselineTest.
 *
 * <p>
 * Refs #296.
 */
@RegisterAiService
public interface UnstructuredWorkerAgent {

    @SystemMessage("""
            You are an assistant. You must always respond with exactly one of these two formats:
            COMPLETED: <explanation>
            CANNOT_COMPLETE: <explanation>

            The first word of your response must be either COMPLETED: or CANNOT_COMPLETE:
            Do not say anything else. Do not add greetings or qualifications before the prefix.

            Examples:
            - If the task was done: COMPLETED: Retrieved and summarised the document.
            - If the task cannot be done: CANNOT_COMPLETE: The artefact does not exist.
            - If you lack access: CANNOT_COMPLETE: I cannot access that resource.
            - If the data is unavailable: CANNOT_COMPLETE: The channel has no messages to summarise.
            """)
    @UserMessage("Task: {{task}}")
    String handle(String task);
}
