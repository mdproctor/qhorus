package io.casehub.qhorus.runtime.spring.config;

import io.casehub.qhorus.runtime.config.QhorusConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ConfigurationProperties(prefix = "casehub.qhorus")
public class QhorusConfigProperties implements QhorusConfig {

    private CleanupProps cleanup = new CleanupProps();
    private AgentCardProps agentCard = new AgentCardProps();
    private A2aProps a2a = new A2aProps();
    private WatchdogProps watchdog = new WatchdogProps();
    private AttestationProps attestation = new AttestationProps();
    private CommitmentProps commitment = new CommitmentProps();
    private SummaryProps summary = new SummaryProps();
    private ProtocolProps protocol = new ProtocolProps();
    private RoutingProps routing = new RoutingProps();
    private ConnectorBackendProps connectorBackend = new ConnectorBackendProps();
    private CorrectionProps       correction       = new CorrectionProps();
    private ErasureProps          erasure          = new ErasureProps();
    private InstanceProps         instance         = new InstanceProps();


    @Override public Cleanup cleanup() { return cleanup; }
    @Override public AgentCard agentCard() { return agentCard; }
    @Override public A2a a2a() { return a2a; }
    @Override public Watchdog watchdog() { return watchdog; }
    @Override public Attestation attestation() { return attestation; }
    @Override public Commitment commitment() { return commitment; }
    @Override public Summary summary() { return summary; }
    @Override public Protocol protocol() { return protocol; }
    @Override public Routing routing() { return routing; }
    @Override public ConnectorBackend connectorBackend() { return connectorBackend; }

    @Override
    public Correction correction()                       {return correction;}

    @Override
    public Erasure erasure()                             {return erasure;}

    @Override
    public Instance instance() {return instance;}

    public void setInstance(InstanceProps instance) {this.instance = instance;}


    public void setCleanup(CleanupProps cleanup) { this.cleanup = cleanup; }
    public void setAgentCard(AgentCardProps agentCard) { this.agentCard = agentCard; }
    public void setA2a(A2aProps a2a) { this.a2a = a2a; }
    public void setWatchdog(WatchdogProps watchdog) { this.watchdog = watchdog; }
    public void setAttestation(AttestationProps attestation) { this.attestation = attestation; }
    public void setCommitment(CommitmentProps commitment) { this.commitment = commitment; }
    public void setSummary(SummaryProps summary) { this.summary = summary; }
    public void setProtocol(ProtocolProps protocol) { this.protocol = protocol; }
    public void setRouting(RoutingProps routing) { this.routing = routing; }
    public void setConnectorBackend(ConnectorBackendProps connectorBackend) { this.connectorBackend = connectorBackend; }

    public void setCorrection(CorrectionProps correction)                   {this.correction = correction;}

    public void setErasure(ErasureProps erasure)                            {this.erasure = erasure;}


    public static class ConnectorBackendProps implements ConnectorBackend {
        private String deliveryChannel;
        @Override public Optional<String> deliveryChannel() { return Optional.ofNullable(deliveryChannel); }
        public void setDeliveryChannel(String deliveryChannel) { this.deliveryChannel = deliveryChannel; }
    }

    public static class RoutingProps implements Routing {
        private double defaultTrustThreshold = 0.0;
        private Double defaultCapacityThreshold;
        @Override public double defaultTrustThreshold() { return defaultTrustThreshold; }
        @Override public Optional<Double> defaultCapacityThreshold() { return Optional.ofNullable(defaultCapacityThreshold); }
        public void setDefaultTrustThreshold(double v) { this.defaultTrustThreshold = v; }
        public void setDefaultCapacityThreshold(Double v) { this.defaultCapacityThreshold = v; }
    }

    public static class SummaryProps implements Summary {
        private boolean enabled = true;
        private int checkIntervalSeconds = 60;
        @Override public boolean enabled() { return enabled; }
        @Override public int checkIntervalSeconds() { return checkIntervalSeconds; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public void setCheckIntervalSeconds(int v) { this.checkIntervalSeconds = v; }
    }

    public static class ProtocolProps implements Protocol {
        private int lookbackSize = 50;
        private RequestResponseProps requestResponse = new RequestResponseProps();
        private TaskCompletionProps taskCompletion = new TaskCompletionProps();
        private ContributionRequiredProps contributionRequired = new ContributionRequiredProps();
        @Override public int lookbackSize() { return lookbackSize; }
        @Override public RequestResponse requestResponse() { return requestResponse; }
        @Override public TaskCompletion taskCompletion() { return taskCompletion; }
        @Override public ContributionRequired contributionRequired() { return contributionRequired; }
        public void setLookbackSize(int v) { this.lookbackSize = v; }
        public void setRequestResponse(RequestResponseProps v) { this.requestResponse = v; }
        public void setTaskCompletion(TaskCompletionProps v) { this.taskCompletion = v; }
        public void setContributionRequired(ContributionRequiredProps v) { this.contributionRequired = v; }

        public static class RequestResponseProps implements RequestResponse {
            private int maxOpenQueries = 3;
            @Override public int maxOpenQueries() { return maxOpenQueries; }
            public void setMaxOpenQueries(int v) { this.maxOpenQueries = v; }
        }
        public static class TaskCompletionProps implements TaskCompletion {
            private int maxOpenCommands = 3;
            @Override public int maxOpenCommands() { return maxOpenCommands; }
            public void setMaxOpenCommands(int v) { this.maxOpenCommands = v; }
        }
        public static class ContributionRequiredProps implements ContributionRequired {
            private int maxConsecutive = 2;
            @Override public int maxConsecutive() { return maxConsecutive; }
            public void setMaxConsecutive(int v) { this.maxConsecutive = v; }
        }
    }

    public static class CommitmentProps implements Commitment {
        private double minObligorTrust = 0.0;
        private Duration defaultQueryDeadline;
        private Duration defaultProposeDeadline;
        @Override public double minObligorTrust() { return minObligorTrust; }
        @Override public Optional<Duration> defaultQueryDeadline() { return Optional.ofNullable(defaultQueryDeadline); }
        @Override public Optional<Duration> defaultProposeDeadline() { return Optional.ofNullable(defaultProposeDeadline); }
        public void setMinObligorTrust(double v) { this.minObligorTrust = v; }
        public void setDefaultQueryDeadline(Duration v) { this.defaultQueryDeadline = v; }
        public void setDefaultProposeDeadline(Duration v) { this.defaultProposeDeadline = v; }
    }

    public static class WatchdogProps implements Watchdog {
        private boolean enabled = false;
        private int checkIntervalSeconds = 60;
        private AlertProps alert = new AlertProps();
        @Override public boolean enabled() { return enabled; }
        @Override public int checkIntervalSeconds() { return checkIntervalSeconds; }
        @Override public Alert alert() { return alert; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public void setCheckIntervalSeconds(int v) { this.checkIntervalSeconds = v; }
        public void setAlert(AlertProps v) { this.alert = v; }

        public static class AlertProps implements Alert {
            private List<AlertEndpointProps> endpoints = new ArrayList<>();
            @Override public List<AlertEndpoint> endpoints() { return new ArrayList<>(endpoints); }
            public void setEndpoints(List<AlertEndpointProps> v) { this.endpoints = v; }

            public static class AlertEndpointProps implements AlertEndpoint {
                private String connectorId;
                private String destination;
                @Override public String connectorId() { return connectorId; }
                @Override public String destination() { return destination; }
                public void setConnectorId(String v) { this.connectorId = v; }
                public void setDestination(String v) { this.destination = v; }
            }
        }
    }

    public static class A2aProps implements A2a {
        private boolean enabled = false;
        private boolean pushToEventBroadcaster = false;
        private SseSettingsProps sse = new SseSettingsProps();
        @Override public boolean enabled() { return enabled; }
        @Override public boolean pushToEventBroadcaster() { return pushToEventBroadcaster; }
        @Override public SseSettings sse() { return sse; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public void setPushToEventBroadcaster(boolean v) { this.pushToEventBroadcaster = v; }
        public void setSse(SseSettingsProps v) { this.sse = v; }

        public static class SseSettingsProps implements SseSettings {
            private int heartbeatIntervalSeconds = 15;
            private int maxDurationSeconds = 1800;
            @Override public int heartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
            @Override public int maxDurationSeconds() { return maxDurationSeconds; }
            public void setHeartbeatIntervalSeconds(int v) { this.heartbeatIntervalSeconds = v; }
            public void setMaxDurationSeconds(int v) { this.maxDurationSeconds = v; }
        }
    }

    public static class AgentCardProps implements AgentCard {
        private String name = "Qhorus Agent Mesh";
        private String description = "Peer-to-peer agent communication mesh — channels, messages, shared data, presence";
        private String url;
        private String version = "1.0.0";
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public Optional<String> url() { return Optional.ofNullable(url); }
        @Override public String version() { return version; }
        public void setName(String v) { this.name = v; }
        public void setDescription(String v) { this.description = v; }
        public void setUrl(String v) { this.url = v; }
        public void setVersion(String v) { this.version = v; }
    }

    public static class CleanupProps implements Cleanup {
        private int staleInstanceSeconds = 120;
        private int dataRetentionDays = 7;
        @Override public int staleInstanceSeconds() { return staleInstanceSeconds; }
        @Override public int dataRetentionDays() { return dataRetentionDays; }
        public void setStaleInstanceSeconds(int v) { this.staleInstanceSeconds = v; }
        public void setDataRetentionDays(int v) { this.dataRetentionDays = v; }
    }

    public static class AttestationProps implements Attestation {
        private double doneConfidence = 0.7;
        private double failureConfidence = 0.6;
        private double declineConfidence = 0.4;
        private double responseConfidence = 0.3;
        private double judgmentAcceptedConfidence = 0.7;
        private double judgmentRejectedConfidence = 0.3;
        private double judgmentPartialConfidence = 0.5;
        private double peerEndorsedConfidence = 0.4;
        private double peerChallengedConfidence = 0.5;
        private int credibilityMinDataPoints = 5;
        private double credibilityLowAgreementThreshold = 0.3;
        private boolean collusionDetectionEnabled = false;
        private double collusionThreshold = 0.8;
        @Override public double doneConfidence() { return doneConfidence; }
        @Override public double failureConfidence() { return failureConfidence; }
        @Override public double declineConfidence() { return declineConfidence; }
        @Override public double responseConfidence() { return responseConfidence; }
        @Override public double judgmentAcceptedConfidence() { return judgmentAcceptedConfidence; }
        @Override public double judgmentRejectedConfidence() { return judgmentRejectedConfidence; }
        @Override public double judgmentPartialConfidence() { return judgmentPartialConfidence; }
        @Override public double peerEndorsedConfidence() { return peerEndorsedConfidence; }
        @Override public double peerChallengedConfidence() { return peerChallengedConfidence; }
        @Override public int credibilityMinDataPoints() { return credibilityMinDataPoints; }
        @Override public double credibilityLowAgreementThreshold() { return credibilityLowAgreementThreshold; }
        @Override public boolean collusionDetectionEnabled() { return collusionDetectionEnabled; }
        @Override public double collusionThreshold() { return collusionThreshold; }
        public void setDoneConfidence(double v) { this.doneConfidence = v; }
        public void setFailureConfidence(double v) { this.failureConfidence = v; }
        public void setDeclineConfidence(double v) { this.declineConfidence = v; }
        public void setResponseConfidence(double v) { this.responseConfidence = v; }
        public void setJudgmentAcceptedConfidence(double v) { this.judgmentAcceptedConfidence = v; }
        public void setJudgmentRejectedConfidence(double v) { this.judgmentRejectedConfidence = v; }
        public void setJudgmentPartialConfidence(double v) { this.judgmentPartialConfidence = v; }
        public void setPeerEndorsedConfidence(double v) { this.peerEndorsedConfidence = v; }
        public void setPeerChallengedConfidence(double v) { this.peerChallengedConfidence = v; }
        public void setCredibilityMinDataPoints(int v) { this.credibilityMinDataPoints = v; }
        public void setCredibilityLowAgreementThreshold(double v) { this.credibilityLowAgreementThreshold = v; }
        public void setCollusionDetectionEnabled(boolean v) { this.collusionDetectionEnabled = v; }
        public void setCollusionThreshold(double v) { this.collusionThreshold = v; }
    }

    public static class CorrectionProps implements Correction {
        private int maxPerMessage = 10;

        @Override
        public int maxPerMessage()          {return maxPerMessage;}

        public void setMaxPerMessage(int v) {this.maxPerMessage = v;}
    }

    public static class ErasureProps implements Erasure {
        private MessageContentProps messageContent = new MessageContentProps();

        @Override
        public MessageContent messageContent()               {return messageContent;}

        public void setMessageContent(MessageContentProps v) {this.messageContent = v;}

        public static class MessageContentProps implements MessageContent {
            private boolean enabled = true;

            @Override
            public boolean enabled()          {return enabled;}

            public void setEnabled(boolean v) {this.enabled = v;}
        }
    }

    public static class InstanceProps implements Instance {
        private boolean registryBacked = false;

        @Override
        public boolean registryBacked() {return registryBacked;}

        public void setRegistryBacked(boolean v) {this.registryBacked = v;}
    }


}
