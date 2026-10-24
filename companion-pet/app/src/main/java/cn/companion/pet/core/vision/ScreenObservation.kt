package cn.companion.pet.core.vision

enum class CapturedStatus {
    OK,
    NOT_CONNECTED,
    NO_PERMISSION,
    COOLDOWN,
    SECURE_WINDOW,
    UNSUPPORTED,
    NO_ACTIVE_WINDOW,
    TIMEOUT,
    ERROR,
}

enum class ObservationSource { A11Y_NODES, SCREENSHOT, VISION }

data class ScreenObservation(
    val id: String,
    val capturedAt: Long,
    val packageName: String,
    val source: ObservationSource,
    val screenshotAvailable: Boolean,
    val nodesAvailable: Boolean,
    val visionAvailable: Boolean,
    val status: CapturedStatus,
    val error: String = "",
    val summary: String = "",
    val expiresAt: Long,
) {
    fun valid(now: Long): Boolean = now <= expiresAt && status == CapturedStatus.OK
}

data class ScreenCaptureAttempt(
    val status: CapturedStatus,
    val jpeg: ByteArray? = null,
    val width: Int = 0,
    val height: Int = 0,
    val error: String = "",
)

object ScreenCapturePolicy {
    const val MAX_DIMENSION = 1280
    const val MAX_JPEG_BYTES = 700 * 1024
    const val MIN_INTERVAL_MILLIS = 2_000L

    fun canCapture(
        packageName: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
        now: Long,
        lastCaptureAt: Long,
        secure: Boolean,
    ): CapturedStatus = when {
        packageName.isNullOrBlank() -> CapturedStatus.NO_ACTIVE_WINDOW
        packageName == ownPackage -> CapturedStatus.NO_ACTIVE_WINDOW
        packageName in excludedPackages -> CapturedStatus.SECURE_WINDOW
        secure -> CapturedStatus.SECURE_WINDOW
        lastCaptureAt > 0 && now - lastCaptureAt < MIN_INTERVAL_MILLIS -> CapturedStatus.COOLDOWN
        else -> CapturedStatus.OK
    }

    fun fitWithin(bytes: ByteArray): ByteArray? =
        if (bytes.isEmpty() || bytes.size > MAX_JPEG_BYTES) null else bytes
}

class ScreenObservationStore {
    @Volatile
    private var latest: ScreenObservation? = null

    fun publish(observation: ScreenObservation) { latest = observation }

    fun latestValid(now: Long): ScreenObservation? = latest?.takeIf { it.valid(now) }

    fun latest(): ScreenObservation? = latest

    fun clear() { latest = null }
}
