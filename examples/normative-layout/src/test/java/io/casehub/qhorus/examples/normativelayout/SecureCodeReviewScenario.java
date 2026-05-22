package io.casehub.qhorus.examples.normativelayout;

import java.util.List;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.DispatchResult;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.data.DataService;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.message.MessageService;

/**
 * Canonical Layer 1 Secure Code Review scenario.
 * Two agents coordinate through 3 normative channels; no LLM required.
 * Importable by Claudony and CaseHub as the Layer 1 reference.
 */
public class SecureCodeReviewScenario {

    public final String caseId;
    public final String workChannel;
    public final String observeChannel;
    public final String oversightChannel;

    private final ChannelService channelService;
    private final InstanceService instanceService;
    private final MessageService messageService;
    private final DataService dataService;

    public SecureCodeReviewScenario(String caseId,
            ChannelService channelService,
            InstanceService instanceService,
            MessageService messageService,
            DataService dataService) {
        this.caseId = caseId;
        this.workChannel = "case-" + caseId + "/work";
        this.observeChannel = "case-" + caseId + "/observe";
        this.oversightChannel = "case-" + caseId + "/oversight";
        this.channelService = channelService;
        this.instanceService = instanceService;
        this.messageService = messageService;
        this.dataService = dataService;
    }

    /** Create the 3-channel normative layout for this case. */
    public void setupChannels() {
        channelService.create(workChannel, "Worker coordination", ChannelSemantic.APPEND,
                null, null, null, null, null, null);
        channelService.create(observeChannel, "Telemetry", ChannelSemantic.APPEND,
                null, null, null, null, null, "EVENT");
        channelService.create(oversightChannel, "Human governance", ChannelSemantic.APPEND,
                null, null, null, null, null, "QUERY,COMMAND");
    }

    public Channel workChannel() {
        return channelService.findByName(workChannel).orElseThrow();
    }

    public Channel observeChannel() {
        return channelService.findByName(observeChannel).orElseThrow();
    }

    public Channel oversightChannel() {
        return channelService.findByName(oversightChannel).orElseThrow();
    }

    /**
     * Researcher receives a COMMAND from the orchestrator, runs analysis, shares artefact,
     * and posts DONE to discharge the obligation.
     *
     * A COMMAND is sent from "system:orchestrator" to researcher before analysis begins.
     * DONE requires both inReplyTo (the COMMAND messageId) and correlationId.
     * The {@code correlationId} parameter identifies the obligation chain; if null a
     * unique ID is generated so callers that don't need explicit correlation still get
     * a valid DONE.
     */
    public DispatchResult runResearcher(String correlationId) {
        instanceService.register("researcher-001", "Security analyst",
                List.of("security", "code-analysis"), null);

        Channel work = workChannel();
        Channel observe = observeChannel();

        String researchCorrId = (correlationId != null) ? correlationId
                : "corr-researcher-" + System.nanoTime();

        // Orchestrator issues a COMMAND to researcher — creates an open obligation
        DispatchResult command = messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("system:orchestrator")
                .type(MessageType.COMMAND)
                .content("Perform security analysis of AuthService.java and TokenRefreshService.java")
                .correlationId(researchCorrId)
                .target("instance:researcher-001")
                .actorType(ActorType.SYSTEM)
                .build());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("researcher-001")
                .type(MessageType.STATUS)
                .content("Starting security analysis of AuthService.java")
                .actorType(ActorTypeResolver.resolve("researcher-001"))
                .build());
        messageService.dispatch(MessageDispatch.builder()
                .channelId(observe.id)
                .sender("researcher-001")
                .type(MessageType.EVENT)
                .content("{\"tool\":\"read_file\",\"path\":\"AuthService.java\"}")
                .actorType(ActorTypeResolver.resolve("researcher-001"))
                .build());
        messageService.dispatch(MessageDispatch.builder()
                .channelId(observe.id)
                .sender("researcher-001")
                .type(MessageType.EVENT)
                .content("{\"tool\":\"read_file\",\"path\":\"TokenRefreshService.java\"}")
                .actorType(ActorTypeResolver.resolve("researcher-001"))
                .build());

        dataService.store("auth-analysis-v1-" + caseId, "Security analysis artefact", "researcher-001",
                "## Security Analysis\nFinding 1: SQL injection — HIGH\nFinding 2: Stale token — MEDIUM",
                false, true);

        // DONE discharges the COMMAND obligation from the orchestrator
        return messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("researcher-001")
                .type(MessageType.DONE)
                .content("Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1")
                .correlationId(researchCorrId)
                .inReplyTo(command.messageId())
                .actorType(ActorTypeResolver.resolve("researcher-001"))
                .build());
    }

    /**
     * Reviewer receives a COMMAND from the orchestrator, queries researcher, receives RESPONSE,
     * shares report, then posts DONE to discharge the reviewer's obligation.
     *
     * A COMMAND is sent from "system:orchestrator" to reviewer before review begins.
     * The {@code doneCorrelationId} identifies the reviewer's obligation chain.
     */
    public DispatchResult runReviewer(String queryCorrelationId, String doneCorrelationId) {
        instanceService.register("reviewer-001", "Security reviewer",
                List.of("review", "security"), null);

        Channel work = workChannel();

        String reviewCorrId = (doneCorrelationId != null) ? doneCorrelationId
                : "corr-reviewer-" + System.nanoTime();

        // Orchestrator issues a COMMAND to reviewer — creates an open obligation
        DispatchResult reviewCommand = messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("system:orchestrator")
                .type(MessageType.COMMAND)
                .content("Review the security analysis findings and produce a final report")
                .correlationId(reviewCorrId)
                .target("instance:reviewer-001")
                .actorType(ActorType.SYSTEM)
                .build());

        DispatchResult query = messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("reviewer-001")
                .type(MessageType.QUERY)
                .content("Finding #3: does TokenRefreshService.java:142 share the same root cause?")
                .correlationId(queryCorrelationId)
                .target("instance:researcher-001")
                .actorType(ActorTypeResolver.resolve("reviewer-001"))
                .build());

        messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("researcher-001")
                .type(MessageType.RESPONSE)
                .content("Yes — same interpolated SQL pattern. One root cause, two surfaces.")
                .correlationId(queryCorrelationId)
                .inReplyTo(query.messageId())
                .actorType(ActorTypeResolver.resolve("researcher-001"))
                .build());

        dataService.store("review-report-v1-" + caseId, "Code review report artefact", "reviewer-001",
                "## Code Review Report\nRoot cause A: SQL injection (CRITICAL)\nRoot cause B: Stale token (HIGH)",
                false, true);

        // DONE discharges the COMMAND obligation from the orchestrator
        return messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id)
                .sender("reviewer-001")
                .type(MessageType.DONE)
                .content("Review complete. Final report: shared-data:review-report-v1")
                .correlationId(reviewCorrId)
                .inReplyTo(reviewCommand.messageId())
                .actorType(ActorTypeResolver.resolve("reviewer-001"))
                .build());
    }
}
