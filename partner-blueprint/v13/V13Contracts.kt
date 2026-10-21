package com.moling.design.v13

/*
 * v13 design contracts, not installed production code.
 * Standalone JVM Kotlin; no Android or serialization dependency.
 * Production DTOs need explicit kotlinx.serialization names and codec tests.
 * Existing Chess/HabitBook/Atlas stay authoritative; DTOs below are projections.
 */

enum class BrainRegion { SENSE, MEMORY, STRATEGY, IGNITION, PREDICTION, PRESENCE, ROUTING }
enum class WorkspaceHand { ACTOR, HARVEST, ARTIFACT, HUD_AUDIO, GATE_SYSTEM }
enum class CapabilityState { READY, NOT_CONFIGURED, NEEDS_PERMISSION, UNSUPPORTED, TEMPORARY_FAILURE }
data class Capability(val name: String, val state: CapabilityState, val detail: String = "")
enum class SourceKind { USER_SHARE, USER_PASTE, SELECTED_SCREEN, RSS, WEB, ACTOR_RESULT, USAGE }
enum class EvidenceStatus { OBSERVED, INFERRED, UNVERIFIED, VERIFIED, DISPUTED }
data class SourceRef(
    val id: String, val kind: SourceKind, val capturedAt: Long,
    val title: String = "", val url: String? = null, val packageName: String? = null,
    val quote: String = "", val contentHash: String = "", val sessionId: String? = null,
    val status: EvidenceStatus = EvidenceStatus.UNVERIFIED,
)
data class AttentionEvidence(
    val from: Long, val until: Long, val appSwitches: Int,
    val uninterruptedPlayMs: Long, val observedScrollEvents: Int?,
    val scrollIntervalVariance: Double?, val confidence: Double,
    val reasons: List<String>, val missingSignals: Set<String>,
)
enum class CaptureMode { TITLE_ONLY, VISIBLE_TEXT, ACTOR_VISIBLE_TURNS }
data class CaptureLease(
    val id: String, val packageNames: Set<String>, val mode: CaptureMode,
    val userStartedAt: Long, val expiresAt: Long, val maxChars: Int = 8192,
    val allowCoordinates: Boolean = false,
)
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class VisibleNode(
    val id: String, val parentId: String?, val role: String,
    val text: String, val bounds: Bounds?, val editable: Boolean = false,
)
data class ScreenSnapshot(
    val id: String, val leaseId: String, val packageName: String,
    val capturedAt: Long, val windowId: String, val windowRevision: Long,
    val displayWidth: Int, val displayHeight: Int, val rotation: Int,
    val nodes: List<VisibleNode>, val contentHash: String,
    val truncated: Boolean, val unavailableReason: String? = null,
)
data class InhaleItem(
    val id: String, val source: SourceRef, val text: String,
    val receivedAt: Long, val expiresAt: Long, val focusId: String?,
    val consumedBy: Set<String> = emptySet(),
)
data class InhaleBuffer(val items: List<InhaleItem> = emptyList(), val maxItems: Int = 200)

data class PortError(val code: String, val message: String, val retryAfterMs: Long? = null)
sealed class PortResult<out T> {
    data class Ok<T>(val value: T) : PortResult<T>()
    data class Failed(val error: PortError) : PortResult<Nothing>()
}
data class WebBudget(val maxSearches: Int, val maxDocuments: Int, val maxBytes: Long, val deadline: Long)
data class SearchRequest(val query: String, val limit: Int, val focusId: String, val budget: WebBudget)
data class WebHit(val source: SourceRef, val snippet: String)
data class SearchPage(val hits: List<WebHit>, val provider: String, val fetchedAt: Long)
data class FetchRequest(val url: String, val focusId: String?, val budget: WebBudget)
data class WebDocument(val source: SourceRef, val text: String, val truncated: Boolean)
interface WebPort {
    fun capabilities(): List<Capability>
    suspend fun search(request: SearchRequest): PortResult<SearchPage>
    suspend fun fetch(request: FetchRequest): PortResult<WebDocument>
    suspend fun readFeed(url: String, limit: Int, budget: WebBudget): PortResult<List<WebHit>>
}

enum class FocusStatus { ACTIVE, PAUSED, ARCHIVED }
data class FocusWorkspace(
    val id: String, val question: String, val goal: String, val status: FocusStatus,
    val startedAt: Long, val expiresAt: Long, val revision: Long,
    val topicId: String?, val sourceIds: List<String>, val artifactIds: List<String>,
    val currentChessId: String? = null, val actorSessionId: String? = null,
    val nextChoice: String? = null,
)
enum class MemoryCandidateStatus { PROPOSED, ACCEPTED, REJECTED }
data class MemoryCandidate(
    val id: String, val focusId: String?, val claim: String,
    val sourceIds: List<String>, val derivedFromArtifactId: String?,
    val status: MemoryCandidateStatus = MemoryCandidateStatus.PROPOSED,
    val exerciseOnly: Boolean = false, val conflictsWith: List<String> = emptyList(),
)
data class RecallRequest(val query: String, val focusId: String?, val topicId: String?, val maxItems: Int = 8)
data class RecallBundle(
    val seedNodeIds: List<String>, val neighborNodeIds: List<String>,
    val relationDescriptions: List<String>, val gaps: List<String>, val sourceIds: List<String>,
)
interface MemoryPort {
    suspend fun recall(request: RecallRequest): RecallBundle
    suspend fun propose(candidate: MemoryCandidate): PortResult<String>
    suspend fun accept(candidateId: String, expectedRevision: Long): PortResult<String>
    suspend fun reject(candidateId: String): PortResult<Unit>
}

enum class ArtifactKind { SOURCE_PACK, OUTLINE, DRAFT, COUNCIL_REVIEW, DISCOVERY, EXERCISE, ACTOR_RESULT }
enum class ArtifactStatus { DRAFT, READY_FOR_REVIEW, ACCEPTED, ARCHIVED }
enum class BlockKind { MARKDOWN, QUOTE, ASSUMPTION, CHOICE, REVIEW_NOTE }
data class ArtifactOption(val id: String, val label: String, val branchId: String?, val explanation: String = "")
data class ArtifactBlock(
    val id: String, val kind: BlockKind, val markdown: String,
    val sourceIds: List<String> = emptyList(), val options: List<ArtifactOption> = emptyList(),
    val exerciseOnly: Boolean = false,
)
data class WorkspaceArtifact(
    val id: String, val focusId: String, val kind: ArtifactKind,
    val title: String, val blocks: List<ArtifactBlock>, val sourceIds: List<String>,
    val createdAt: Long, val updatedAt: Long, val revision: Long,
    val status: ArtifactStatus = ArtifactStatus.DRAFT,
    val parentArtifactId: String? = null, val branchId: String = "main",
    val producerJobId: String? = null, val unresolvedQuestions: List<String> = emptyList(),
)
enum class ArtifactActionKind { EDIT_BLOCK, ACCEPT_BLOCK, DELETE_BLOCK, CHOOSE_BRANCH, REQUEST_CRITIC, REWRITE_BLOCK, ACCEPT_ARTIFACT }
data class ArtifactAction(
    val id: String, val artifactId: String, val expectedRevision: Long,
    val kind: ArtifactActionKind, val blockId: String?, val optionId: String?,
    val replacementMarkdown: String?, val at: Long,
)
interface WorkspacePort {
    suspend fun load(id: String): WorkspaceArtifact?
    suspend fun save(artifact: WorkspaceArtifact, expectedRevision: Long?): PortResult<WorkspaceArtifact>
    suspend fun apply(action: ArtifactAction): PortResult<WorkspaceArtifact>
    suspend fun listForFocus(focusId: String): List<WorkspaceArtifact>
}
enum class JobStatus { QUEUED, RUNNING, WAITING_INPUT, READY, FAILED, CANCELLED, SUPERSEDED }
data class PreworkJob(
    val id: String, val focusId: String, val focusRevision: Long,
    val taskBrief: String, val deliverable: ArtifactKind,
    val sourceIds: List<String>, val inputHash: String,
    val status: JobStatus, val createdAt: Long, val deadline: Long,
    val modelAttempts: Int = 0, val artifactId: String? = null, val error: PortError? = null,
)
data class CouncilReview(
    val artifactId: String, val artifactRevision: Long,
    val coach: List<String>, val strategist: List<String>,
    val critic: List<String>, val friend: List<String>, val blockIds: List<String>,
)

enum class IgnitionStage { FOG, TOLL, CHESS, QUIET }
data class QuickChoice(val id: String, val label: String, val action: String)
// Wire/read-model projection of the existing core.spar.Chess, not a second engine.
data class ThreeTurnChess(
    val id: String, val focusId: String?, val nodeId: String, val turn: Int,
    val bait: String, val choices: List<QuickChoice>, val moves: List<String>,
    val sourceIds: List<String>, val exerciseOnly: Boolean = true,
    val score: Int? = null, val conclusionCandidateId: String? = null,
)
data class IgnitionTurn(
    val sessionId: String, val stage: IgnitionStage, val round: Int,
    val line: String, val choices: List<QuickChoice>, val done: Boolean,
    val sourceIds: List<String>,
)
enum class BetKind { CHOICE, REPLY, COMPLETE_ROUND }
enum class BetOutcome { PENDING, HIT, MISS, UNOBSERVABLE, CANCELLED }
data class ChoiceBet(
    val id: String, val kind: BetKind, val sessionId: String,
    val predictedOptionId: String?, val dueAt: Long, val madeAt: Long,
    val probability: Double?, val evidenceIds: List<String>,
    val outcome: BetOutcome = BetOutcome.PENDING,
)

enum class GlitchKind { NONE, BUBBLE_POKE, WHISPER_TTS, DANMAKU_BET }
enum class PresenceMode { CARD, PET, WHISPER, COUNCIL, QUIZ, RADIO }
enum class ExposureOutcome { REPLIED, USEFUL, SKIPPED, NO_RESPONSE, NOT_OBSERVED, EXPLICIT_STOP }
data class ContextBucket(val hourBand: Int, val activityClass: String, val ignitionStage: IgnitionStage?)
data class Exposure(
    val id: String, val context: ContextBucket, val mode: PresenceMode,
    val shownAt: Long, val outcome: ExposureOutcome, val contentId: String,
)
data class ModalityStats(
    val context: ContextBucket, val mode: PresenceMode,
    val eligibleExposures: Int, val replies: Int, val useful: Int, val explicitStops: Int,
    val coolingUntil: Long = 0,
)
data class CognitiveRule(
    val id: String, val whenText: String, val thenText: String,
    val evidenceIds: List<String>, val accepted: Boolean = false,
)
data class CognitiveProfile(
    val revision: Long = 0, val stats: List<ModalityStats> = emptyList(),
    val rules: List<CognitiveRule> = emptyList(), val quietUntil: Long = 0,
)
data class PresenceBudget(val remainingToday: Int, val nextAllowedAt: Long, val quietUntil: Long)
data class PresenceDecision(val mode: PresenceMode?, val glitch: GlitchKind, val why: String, val maxDurationMs: Long)
interface HabituationTracker {
    fun record(profile: CognitiveProfile, exposure: Exposure): CognitiveProfile
    fun choose(profile: CognitiveProfile, context: ContextBucket, available: Set<PresenceMode>,
               budget: PresenceBudget, now: Long, roll: Double): PresenceDecision
}
enum class RadioStatus { PREPARING, PLAYING, PAUSED, FINISHED, STOPPED }
data class RadioSegment(val id: String, val text: String, val sourceIds: List<String>, val artifactId: String?)
data class RadioSession(
    val id: String, val focusId: String?, val segments: List<RadioSegment>,
    val position: Int, val status: RadioStatus, val startedAt: Long, val deadline: Long,
    val explicitlyStarted: Boolean,
)
data class VoiceSession(val id: String, val userStartedAt: Long, val expiresAt: Long, val listening: Boolean)

enum class ActorKind { OFFICIAL_APP, RELAY_MCP, MODEL_API }
enum class ActorStatus { PREPARED, OPENED, WAITING_RESULT, RESULT_RECEIVED, REVIEWED, CANCELLED, EXPIRED }
data class ActorTarget(val id: String, val kind: ActorKind, val packageName: String?, val capabilities: Set<String>)
data class ActorPayload(
    val focusId: String, val question: String, val outline: String,
    val gaps: List<String>, val instruction: String, val sourceIds: List<String>,
)
data class ActorSession(
    val id: String, val targetId: String, val focusId: String,
    val artifactId: String?, val focusRevision: Long, val payloadHash: String,
    val status: ActorStatus, val startedAt: Long, val expiresAt: Long,
    val captureLeaseId: String? = null,
)
data class ActorResult(
    val id: String, val sessionId: String, val text: String,
    val source: SourceRef, val receivedAt: Long, val partial: Boolean,
    val contentHash: String,
)
interface ActorBridge {
    fun targets(): List<ActorTarget>
    suspend fun prepare(targetId: String, payload: ActorPayload): PortResult<ActorSession>
    suspend fun open(sessionId: String): PortResult<Unit>
    suspend fun poll(sessionId: String, cursor: String?): PortResult<List<ActorResult>>
    suspend fun receive(result: ActorResult): PortResult<Unit>
    suspend fun close(sessionId: String): PortResult<Unit>
}

enum class PlanAdoption { DRAFT, LOCAL_ADOPTED, EXECUTION_REQUESTED, ZK_PENDING, ACTIVE, REJECTED, EXPIRED }
data class FlowAdoption(
    val planId: String, val planRevision: Long, val state: PlanAdoption,
    val optOutPolicyId: String?, val visibleSince: Long?, val eligibleAt: Long?,
    val commandIds: List<String>, val lastReceiptId: String?,
)
data class OptOutPolicy(
    val id: String, val userEnabledAt: Long, val waitVisibleMs: Long,
    val localAdoption: Boolean, val allowedGrantKinds: Set<String>,
)
enum class ChallengeStatus { ISSUED, SUBMITTED, PASSED, FAILED, EXPIRED, REDEEMED, REVOKED }
data class CognitiveUnlockPolicy(
    val id: String, val allowedPackages: Set<String>, val minimumPasses: Int,
    val minutesPerPass: Int, val dailyMaxMinutes: Int, val dailyMaxPasses: Int,
    val userEnabledAt: Long, val policyRevision: Long,
)
data class CognitiveChallenge(
    val id: String, val nonce: String, val policyId: String,
    val packageName: String, val issuedAt: Long, val expiresAt: Long,
    val rubricVersion: Int, val questionIds: List<String>, val status: ChallengeStatus,
)
data class ChallengeAnswer(val questionId: String, val optionId: String)
data class ChallengeQuestion(val id: String, val stem: String, val options: List<QuickChoice>)
data class ChallengeBundle(val challenge: CognitiveChallenge, val questions: List<ChallengeQuestion>)
data class UnlockSubmission(
    val requestId: String, val challengeId: String, val nonce: String,
    val answers: List<ChallengeAnswer>, val submittedAt: Long,
)
// Opaque ID issued and stored by Zongkong, not a self-signed Moling command.
data class TemporaryPass(
    val id: String, val challengeId: String, val packageName: String,
    val issuedAt: Long, val expiresAt: Long, val minutes: Int,
    val policyRevision: Long, val consumed: Boolean,
)
data class ExecutionReceipt(
    val id: String, val requestId: String, val state: String,
    val at: Long, val message: String, val pass: TemporaryPass? = null,
)
interface GateV2Port {
    suspend fun capabilities(): List<Capability>
    suspend fun beginCapture(lease: CaptureLease): PortResult<CaptureLease>
    suspend fun readSnapshots(leaseId: String, cursor: String?): PortResult<SnapshotBatch>
    suspend fun endCapture(leaseId: String): PortResult<Unit>
    suspend fun issueChallenge(policyId: String, packageName: String, requestId: String): PortResult<ChallengeBundle>
    suspend fun submitChallenge(submission: UnlockSubmission): PortResult<ExecutionReceipt>
    suspend fun receipt(requestId: String): PortResult<ExecutionReceipt>
}
data class SnapshotBatch(val items: List<ScreenSnapshot>, val nextCursor: String?, val gap: Boolean)
data class ProviderRequest(
    val version: Int = 2, val requestId: String, val method: String,
    val sessionId: String?, val createdAt: Long, val expiresAt: Long,
    val payloadJson: String,
)
data class ProviderResponse(
    val version: Int = 2, val requestId: String, val ok: Boolean,
    val code: String, val payloadJson: String?, val nextCursor: String?,
    val retryAfterMs: Long?,
)

enum class CycleGoal { CONTINUE_FOCUS, PREWORK, CURIOSITY, FOG, SPAR, ACTOR_RETURN, QUIET }
data class CycleEvent(
    val id: String, val type: String, val at: Long, val sourceId: String?,
    val focusId: String?, val dedupeKey: String,
)
data class CycleDecision(
    val eventId: String, val goal: CycleGoal, val focusId: String?,
    val reason: String, val jobId: String?, val artifactId: String?,
)
data class AutonomyBudget(
    val day: String, val searchesUsed: Int, val documentsUsed: Int,
    val backgroundModelJobsUsed: Int, val unreadProactiveArtifacts: Int,
)
data class BrainV13State(
    val schemaVersion: Int = 13, val focus: FocusWorkspace? = null,
    val profile: CognitiveProfile = CognitiveProfile(),
    val activeJobIds: List<String> = emptyList(), val activeActorSessionId: String? = null,
    val activeRadioSessionId: String? = null, val flowAdoption: FlowAdoption? = null,
    val lastAppliedEventId: String? = null,
)
// Large text, sources, artifacts, event log and outbox live outside the Brain JSON.
// Production repository must commit state transition + event + outbox atomically.
interface CognitiveCycle {
    suspend fun handle(event: CycleEvent): PortResult<CycleDecision>
    suspend fun resumePending(now: Long): PortResult<List<CycleDecision>>
}
