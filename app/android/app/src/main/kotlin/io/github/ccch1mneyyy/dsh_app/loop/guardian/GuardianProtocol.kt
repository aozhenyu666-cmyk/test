package io.github.ccch1mneyyy.dsh_app.loop.guardian

import java.security.MessageDigest

/**
 * Shared health, pairing, policy-mirror, and offline-incident wire contract between
 * DSH Go (`*.dsh_app` / `*.dsh_app.fish`) and `大肥鱼哨兵` (`io.github.ccch1mneyyy.dsh_app.fish.guardian`).
 *
 * Pure Kotlin (no Android framework dependencies) so every auth check, JSON serialization,
 * and offline-queue transition can be unit-tested deterministically on JVM.
 */
object GuardianProtocol {
    const val PROTOCOL_VERSION = 1
    const val GUARDIAN_PKG = "io.github.ccch1mneyyy.dsh_app.fish.guardian"
    const val CLIENT_FISH_PKG = "io.github.ccch1mneyyy.dsh_app.fish"
    const val CLIENT_BASE_PKG = "io.github.ccch1mneyyy.dsh_app"
    const val CERT_HEX_LEN = 64
    const val PAIR_CODE_TTL_MS = 10 * 60_000L

    // ContentProvider methods exposed on `${applicationId}.guardian_health`.
    const val M_PING = "ping"
    const val M_PAIR = "pair"
    const val M_CHALLENGE = "challenge"
    const val M_ENSURE_ENGINE = "ensureEngine"
    const val M_REPORT_OFFLINE = "reportOfflineEvents"

    // Common Bundle keys.
    const val K_OK = "ok"
    const val K_ERROR = "error"
    const val K_V = "v"
    const val K_PKG = "pkg"
    const val K_TARGET_PKG = "targetPkg"
    const val K_CERT = "certSha256"
    const val K_OWN_CERT = "ownCertSha256"
    const val K_PAIR_TOKEN = "pairToken"
    const val K_PAIR_CODE = "pairCode"
    const val K_PAIR_EXPIRES_AT = "pairExpiresAtWall"
    const val K_PAIRED = "paired"
    const val K_SAME_CERT = "sameCert"
    const val K_PAIR_REQUIRED = "pairCodeRequired"

    // Health challenge & engine telemetry keys.
    const val K_NONCE = "nonce"
    const val K_SEQ = "seq"
    const val K_BUDGET_MS = "budgetMs"
    const val K_A11Y_CONNECTED = "a11yConnected"
    const val K_HANDLED_AT_MONO = "handledAtMono"
    const val K_A11Y_INSTANCE = "a11yInstanceId"
    const val K_EVENT_SEQ = "eventSeq"
    const val K_EVENT_AT_MONO = "eventAtMono"
    const val K_ENGINE_PHASE = "enginePhase"
    const val K_SERVICE_RUNNING = "dshServiceRunning"
    const val K_PROACTIVE_TRANSPORT = "proactiveTransport"
    const val K_LAST_PLAN_AT = "lastPlanAtWall"
    const val K_POLICY_MIRROR = "policyMirrorJson"
    const val K_WAKE_RESULT = "wakeResult"
    const val K_NEEDS_FOREGROUND = "needsForeground"
    const val K_OFFLINE_EVENTS_JSON = "offlineEventsJson"
    const val K_ACCEPTED_COUNT = "acceptedCount"
}

/** Verification outcome from [GuardianAuthCore]. Never leaks secret token values in [error]. */
data class GuardianAuthOutcome(
    val ok: Boolean,
    val trustedBy: String = "",
    val error: String = "",
    val actualCertSha256: String = "",
)

/**
 * Caller authentication for ContentProvider Binder calls from `大肥鱼哨兵`.
 *
 * Defense-in-depth rules:
 * 1. Reject UID 0 and empty package declarations.
 * 2. Require `declaredPkg == GUARDIAN_PKG` and verify `Binder.getCallingUid()` actually owns it.
 * 3. Require valid 64-hex SHA-256 signing cert digest read from PackageManager.
 * 4. Trust only when either:
 *    - `actualCertSha256` matches DSH Go's own signing cert (`same_cert`), OR
 *    - caller holds both the pinned `certSha256` AND matching constant-time `pairToken` (`paired`).
 */
object GuardianAuthCore {
    fun isValidHexSha256(s: String?): Boolean =
        s != null && s.length == GuardianProtocol.CERT_HEX_LEN && s.all {
            it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f'
        }

    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(ab, bb)
    }

    fun verifyPingUid(callingUid: Int, uidPackages: List<String>, declaredPkg: String): String? {
        if (callingUid <= 0) return "invalid_uid"
        if (declaredPkg != GuardianProtocol.GUARDIAN_PKG) return "unexpected_package:$declaredPkg"
        if (declaredPkg !in uidPackages) return "uid_package_mismatch"
        return null
    }

    fun verifyCaller(
        callingUid: Int,
        uidPackages: List<String>,
        declaredPkg: String,
        actualCertSha256: String?,
        ownCertSha256: String?,
        pinnedCertSha256: String?,
        suppliedPairToken: String?,
        storedPairToken: String?,
    ): GuardianAuthOutcome {
        val uidErr = verifyPingUid(callingUid, uidPackages, declaredPkg)
        if (uidErr != null) return GuardianAuthOutcome(ok = false, error = uidErr)
        if (!isValidHexSha256(actualCertSha256)) {
            return GuardianAuthOutcome(ok = false, error = "invalid_caller_cert")
        }
        val normalizedActual = actualCertSha256!!.uppercase()
        if (isValidHexSha256(ownCertSha256) && normalizedActual.equals(ownCertSha256, ignoreCase = true)) {
            return GuardianAuthOutcome(
                ok = true,
                trustedBy = "same_cert",
                actualCertSha256 = normalizedActual,
            )
        }
        if (isValidHexSha256(pinnedCertSha256) &&
            normalizedActual.equals(pinnedCertSha256, ignoreCase = true) &&
            !storedPairToken.isNullOrBlank() &&
            constantTimeEquals(suppliedPairToken, storedPairToken)
        ) {
            return GuardianAuthOutcome(
                ok = true,
                trustedBy = "paired",
                actualCertSha256 = normalizedActual,
            )
        }
        val reason = if (pinnedCertSha256.isNullOrBlank() || storedPairToken.isNullOrBlank()) {
            "not_paired"
        } else {
            "cert_or_token_mismatch"
        }
        return GuardianAuthOutcome(
            ok = false,
            error = reason,
            actualCertSha256 = normalizedActual,
        )
    }
}

/**
 * Snapshot of active enforcement rules & live telemetry mirrored from DSH Go to Guardian.
 */
data class GuardianPolicySnapshot(
    val v: Int = GuardianProtocol.PROTOCOL_VERSION,
    val updatedAtWall: Long = 0L,
    val enginePhase: String = "unknown",
    val dshServiceRunning: Boolean = false,
    val proactiveTransport: Boolean = false,
    val a11yConnected: Boolean = false,
    val planActive: Boolean = false,
    val primaryKind: String = "",
    val primaryWhy: String = "",
    val lockUntil: Long = 0L,
    val refuseUntil: Long = 0L,
    val breakUntil: Long = 0L,
    val emergencyUntil: Long = 0L,
    val sessionUntil: Long = 0L,
    val sessionGraceMs: Long = 45_000L,
    val blocked: List<String> = emptyList(),
    val blockWhys: Map<String, String> = emptyMap(),
    val allow: List<String> = emptyList(),
    val todayMinutes: Map<String, Int> = emptyMap(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "v" to v,
        "updatedAtWall" to updatedAtWall,
        "enginePhase" to enginePhase,
        "dshServiceRunning" to dshServiceRunning,
        "proactiveTransport" to proactiveTransport,
        "a11yConnected" to a11yConnected,
        "planActive" to planActive,
        "primaryKind" to primaryKind,
        "primaryWhy" to primaryWhy,
        "lockUntil" to lockUntil,
        "refuseUntil" to refuseUntil,
        "breakUntil" to breakUntil,
        "emergencyUntil" to emergencyUntil,
        "sessionUntil" to sessionUntil,
        "sessionGraceMs" to sessionGraceMs,
        "blocked" to blocked,
        "blockWhys" to blockWhys,
        "allow" to allow,
        "todayMinutes" to todayMinutes,
    )

    fun toJson(): String = GuardianJson.encode(toMap())

    companion object {
        fun fromJson(text: String): GuardianPolicySnapshot? {
            if (text.isBlank()) return null
            val m = GuardianJson.parse(text) as? Map<*, *> ?: return null
            return fromMap(m)
        }

        fun fromMap(m: Map<*, *>): GuardianPolicySnapshot {
            val blockedList = (m["blocked"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val allowList = (m["allow"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val whys = LinkedHashMap<String, String>()
            (m["blockWhys"] as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && v is String) whys[k] = v
            }
            val mins = LinkedHashMap<String, Int>()
            (m["todayMinutes"] as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && v is Number) mins[k] = v.toInt()
            }
            return GuardianPolicySnapshot(
                v = (m["v"] as? Number)?.toInt() ?: GuardianProtocol.PROTOCOL_VERSION,
                updatedAtWall = (m["updatedAtWall"] as? Number)?.toLong() ?: 0L,
                enginePhase = (m["enginePhase"] as? String).orEmpty().ifBlank { "unknown" },
                dshServiceRunning = m["dshServiceRunning"] == true,
                proactiveTransport = m["proactiveTransport"] == true,
                a11yConnected = m["a11yConnected"] == true,
                planActive = m["planActive"] == true,
                primaryKind = (m["primaryKind"] as? String).orEmpty(),
                primaryWhy = (m["primaryWhy"] as? String).orEmpty(),
                lockUntil = (m["lockUntil"] as? Number)?.toLong() ?: 0L,
                refuseUntil = (m["refuseUntil"] as? Number)?.toLong() ?: 0L,
                breakUntil = (m["breakUntil"] as? Number)?.toLong() ?: 0L,
                emergencyUntil = (m["emergencyUntil"] as? Number)?.toLong() ?: 0L,
                sessionUntil = (m["sessionUntil"] as? Number)?.toLong() ?: 0L,
                sessionGraceMs = (m["sessionGraceMs"] as? Number)?.toLong() ?: 45_000L,
                blocked = blockedList,
                blockWhys = whys,
                allow = allowList,
                todayMinutes = mins,
            )
        }
    }
}

/**
 * Incident recorded locally by `大肥鱼哨兵` while the engine was stopped/unresponsive
 * (e.g., `engine_down`, `guardian_emergency_pass`, `screen_lock_attempted`, `recovery_requested`).
 */
data class GuardianOfflineIncident(
    val id: String,
    val type: String,
    val atWall: Long,
    val detail: String = "",
    val reason: String = "",
    val enginePhase: String = "",
    val healthStatus: String = "",
    val pkg: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "type" to type,
        "atWall" to atWall,
        "detail" to detail,
        "reason" to reason,
        "enginePhase" to enginePhase,
        "healthStatus" to healthStatus,
        "pkg" to pkg,
    )

    companion object {
        fun fromMap(m: Map<*, *>): GuardianOfflineIncident? {
            val id = (m["id"] as? String)?.trim().orEmpty()
            val type = (m["type"] as? String)?.trim().orEmpty()
            if (id.isEmpty() || type.isEmpty()) return null
            return GuardianOfflineIncident(
                id = id.take(80),
                type = type.take(64),
                atWall = (m["atWall"] as? Number)?.toLong() ?: 0L,
                detail = (m["detail"] as? String).orEmpty().take(300),
                reason = (m["reason"] as? String).orEmpty().take(200),
                enginePhase = (m["enginePhase"] as? String).orEmpty().take(32),
                healthStatus = (m["healthStatus"] as? String).orEmpty().take(32),
                pkg = (m["pkg"] as? String).orEmpty().take(120),
            )
        }

        fun decodeList(json: String): List<GuardianOfflineIncident> {
            if (json.isBlank()) return emptyList()
            val raw = GuardianJson.parse(json) as? List<*> ?: return emptyList()
            return raw.mapNotNull { item -> (item as? Map<*, *>)?.let(::fromMap) }
        }

        fun encodeList(items: List<GuardianOfflineIncident>): String =
            GuardianJson.encode(items.map { it.toMap() })
    }
}

/**
 * Compact pure-Kotlin RFC 8259 JSON encoder & parser so JVM unit tests run without
 * Android's stubbed `org.json` runtime.
 */
object GuardianJson {
    fun encode(value: Any?): String = StringBuilder().also { appendValue(it, value) }.toString()

    private fun appendValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(v.toString())
            }
            is String -> appendString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, elem) in v) {
                    if (k == null) continue
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, elem)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (elem in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, elem)
                }
                sb.append(']')
            }
            else -> appendString(sb, v.toString())
        }
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? = Parser(text).parseRoot()

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (pos != src.length) throw IllegalArgumentException("Trailing content at $pos")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // '{'
            skipWs()
            val out = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (!peek('"')) throw IllegalArgumentException("Expected key at $pos")
                val key = parseString()
                skipWs()
                if (!consume(':')) throw IllegalArgumentException("Expected ':' at $pos")
                val value = parseValue()
                out[key] = value
                skipWs()
                if (consume('}')) return out
                if (!consume(',')) throw IllegalArgumentException("Expected ',' or '}' at $pos")
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // '['
            skipWs()
            val out = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (consume(']')) return out
                if (!consume(',')) throw IllegalArgumentException("Expected ',' or ']' at $pos")
            }
        }

        private fun parseString(): String {
            if (!consume('"')) throw IllegalArgumentException("Expected '\"' at $pos")
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                if (ch == '"') return sb.toString()
                if (ch == '\\') {
                    if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) throw IllegalArgumentException("Invalid \\u escape")
                            val code = src.substring(pos, pos + 4).toInt(16)
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$esc at ${pos - 1}")
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val raw = src.substring(start, pos)
            return if (isFloat) raw.toDouble() else {
                val l = raw.toLong()
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
        }

        private fun <T> expectLiteral(lit: String, value: T): T {
            if (!src.startsWith(lit, pos)) throw IllegalArgumentException("Expected $lit at $pos")
            pos += lit.length
            return value
        }

        private fun skipWs() {
            while (pos < src.length && src[pos] in " \t\r\n") pos++
        }

        private fun peek(c: Char): Boolean = pos < src.length && src[pos] == c

        private fun consume(c: Char): Boolean {
            if (!peek(c)) return false
            pos++
            return true
        }
    }
}
