package cn.companion.pet.core.device

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    NO_PERMISSION,
    READY,
    DISCONNECTED,
    UNSUPPORTED,
}

data class ShizukuDiagnostic(val status: ShizukuStatus, val detail: String = "")

interface ShizukuProbe {
    fun diagnostic(): ShizukuDiagnostic
    /** Only shows the system permission dialog; never silently elevates. */
    fun requestPermission(): Boolean
}

/** Pure mapping so JVM tests can cover every state without Shizuku installed. */
object ShizukuStatusResolver {
    fun resolve(installed: Boolean, binderAlive: Boolean, preV11: Boolean, granted: Boolean): ShizukuStatus = when {
        !installed -> ShizukuStatus.NOT_INSTALLED
        preV11 -> ShizukuStatus.UNSUPPORTED
        !binderAlive -> ShizukuStatus.NOT_RUNNING
        !granted -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }
}
