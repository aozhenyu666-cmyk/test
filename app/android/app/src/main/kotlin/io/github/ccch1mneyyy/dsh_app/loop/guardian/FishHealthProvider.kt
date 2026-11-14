package io.github.ccch1mneyyy.dsh_app.loop.guardian

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Out-of-band Binder endpoint for `大肥鱼哨兵` (`io.github.ccch1mneyyy.dsh_app.fish.guardian`).
 *
 * Registered at `${applicationId}.guardian_health`. Handles:
 *  - `ping`: reveals basic presence & pairing requirement to the verified guardian package;
 *  - `pair`: establishes same-cert or 6-digit pairCode pin + token;
 *  - `challenge`: verifies a11y main-looper liveness (honestly returns `a11yConnected=false` &
 *    `handledAtMono=0` when unbound) alongside `RuntimeController.phase` and `GuardianPolicyMirror`;
 *  - `ensureEngine`: wakes `DshService` / `RuntimeController.startProcess()`;
 *  - `reportOfflineEvents`: ingests guardian offline logs for replay to `/event`.
 */
class FishHealthProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return err("no_context")
        val e = extras ?: Bundle()
        val uid = Binder.getCallingUid()
        return try {
            when (method) {
                GuardianProtocol.M_PING -> handlePing(c, e, uid)
                GuardianProtocol.M_PAIR -> handlePair(c, e, uid)
                GuardianProtocol.M_CHALLENGE -> handleChallenge(c, e, uid)
                GuardianProtocol.M_ENSURE_ENGINE -> handleEnsureEngine(c, e, uid)
                GuardianProtocol.M_REPORT_OFFLINE -> handleReportOffline(c, e, uid)
                else -> err("unknown_method:$method")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method failed", t)
            err(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun handlePing(c: Context, e: Bundle, uid: Int): Bundle {
        val declaredPkg = e.getString(GuardianProtocol.K_PKG).orEmpty()
        val uidErr = GuardianAuthCore.verifyPingUid(uid, GuardianPairing.uidPackages(c, uid), declaredPkg)
        if (uidErr != null) return err(uidErr)
        val st = GuardianPairing.statusMap(c)
        val ctrl = RuntimeController.get(c)
        val paired = st["paired"] == true
        val sameCert = st["sameCert"] == true
        return ok().apply {
            putInt(GuardianProtocol.K_V, GuardianProtocol.PROTOCOL_VERSION)
            putString(GuardianProtocol.K_TARGET_PKG, c.packageName)
            putBoolean(GuardianProtocol.K_SAME_CERT, sameCert)
            putBoolean(GuardianProtocol.K_PAIRED, paired)
            putBoolean(GuardianProtocol.K_PAIR_REQUIRED, !paired)
            putString(GuardianProtocol.K_ENGINE_PHASE, ctrl.phase.wire)
            putBoolean(GuardianProtocol.K_SERVICE_RUNNING, DshService.isRunning)
            putBoolean(GuardianProtocol.K_A11Y_CONNECTED, PhoneA11yService.isConnected())
        }
    }

    private fun handlePair(c: Context, e: Bundle, uid: Int): Bundle {
        val declaredPkg = e.getString(GuardianProtocol.K_PKG).orEmpty()
        val code = e.getString(GuardianProtocol.K_PAIR_CODE).orEmpty()
        val res = GuardianPairing.handlePair(c, uid, declaredPkg, code)
        if (!res.ok) return err(res.error)
        return ok().apply {
            putInt(GuardianProtocol.K_V, GuardianProtocol.PROTOCOL_VERSION)
            putString(GuardianProtocol.K_PAIR_TOKEN, res.pairToken)
            putBoolean(GuardianProtocol.K_SAME_CERT, res.sameCert)
            putString(GuardianProtocol.K_OWN_CERT, GuardianPairing.certSha256(c, c.packageName).orEmpty())
        }
    }

    private fun handleChallenge(c: Context, e: Bundle, uid: Int): Bundle {
        val declaredPkg = e.getString(GuardianProtocol.K_PKG).orEmpty()
        val token = e.getString(GuardianProtocol.K_PAIR_TOKEN)
        val auth = GuardianPairing.authenticate(c, uid, declaredPkg, token)
        if (!auth.ok) return err(auth.error)

        val nonce = e.getString(GuardianProtocol.K_NONCE).orEmpty()
        val seq = e.getLong(GuardianProtocol.K_SEQ, 0L)
        val budgetMs = e.getLong(GuardianProtocol.K_BUDGET_MS, 2_500L).coerceIn(200L, 4_500L)

        // Dispatch onto PhoneA11yService's main looper when bound; never spoof handledAtMono if unbound.
        val svc = PhoneA11yService.instance
        val handledAtMono = AtomicLong(0L)
        var eventSeq = 0L
        var eventAtMono = 0L
        var a11yInstanceId = ""
        if (svc != null) {
            val latch = CountDownLatch(1)
            val posted = Handler(svc.mainLooper).post {
                handledAtMono.set(SystemClock.elapsedRealtime())
                val healthSnap = runCatching { PhoneA11yService.health() }.getOrNull()
                eventAtMono = healthSnap?.lastEventAt ?: 0L
                val probeAtMono = healthSnap?.lastProbeAt ?: 0L
                eventSeq = maxOf(eventAtMono, probeAtMono)
                a11yInstanceId = "a11y-${System.identityHashCode(svc)}"
                latch.countDown()
            }
            if (posted) {
                runCatching { latch.await(budgetMs, TimeUnit.MILLISECONDS) }
            }
        }

        val ctrl = RuntimeController.get(c)
        val baseSnapshot = GuardianPolicyMirror.current(c)
        val liveSnapshot = baseSnapshot.copy(
            enginePhase = ctrl.phase.wire,
            dshServiceRunning = DshService.isRunning,
            proactiveTransport = ProactiveRuntime.agentPort != null,
            a11yConnected = svc != null && handledAtMono.get() > 0L,
        )

        return ok().apply {
            putInt(GuardianProtocol.K_V, GuardianProtocol.PROTOCOL_VERSION)
            putString(GuardianProtocol.K_TARGET_PKG, c.packageName)
            putString(GuardianProtocol.K_NONCE, nonce)
            putLong(GuardianProtocol.K_SEQ, seq)
            putBoolean(GuardianProtocol.K_A11Y_CONNECTED, svc != null)
            putLong(GuardianProtocol.K_HANDLED_AT_MONO, handledAtMono.get())
            putString(GuardianProtocol.K_A11Y_INSTANCE, a11yInstanceId)
            putLong(GuardianProtocol.K_EVENT_SEQ, eventSeq)
            putLong(GuardianProtocol.K_EVENT_AT_MONO, eventAtMono)
            putString(GuardianProtocol.K_ENGINE_PHASE, ctrl.phase.wire)
            putBoolean(GuardianProtocol.K_SERVICE_RUNNING, DshService.isRunning)
            putBoolean(GuardianProtocol.K_PROACTIVE_TRANSPORT, ProactiveRuntime.agentPort != null)
            putLong(GuardianProtocol.K_LAST_PLAN_AT, liveSnapshot.updatedAtWall)
            putString(GuardianProtocol.K_POLICY_MIRROR, liveSnapshot.toJson())
        }
    }

    private fun handleEnsureEngine(c: Context, e: Bundle, uid: Int): Bundle {
        val declaredPkg = e.getString(GuardianProtocol.K_PKG).orEmpty()
        val token = e.getString(GuardianProtocol.K_PAIR_TOKEN)
        val auth = GuardianPairing.authenticate(c, uid, declaredPkg, token)
        if (!auth.ok) return err(auth.error)

        val res = DshService.ensureEngine(c)
        return ok().apply {
            putInt(GuardianProtocol.K_V, GuardianProtocol.PROTOCOL_VERSION)
            putString(GuardianProtocol.K_WAKE_RESULT, res.status)
            putBoolean(GuardianProtocol.K_NEEDS_FOREGROUND, res.needsForeground)
            putString(GuardianProtocol.K_ENGINE_PHASE, RuntimeController.get(c).phase.wire)
        }
    }

    private fun handleReportOffline(c: Context, e: Bundle, uid: Int): Bundle {
        val declaredPkg = e.getString(GuardianProtocol.K_PKG).orEmpty()
        val token = e.getString(GuardianProtocol.K_PAIR_TOKEN)
        val auth = GuardianPairing.authenticate(c, uid, declaredPkg, token)
        if (!auth.ok) return err(auth.error)

        val json = e.getString(GuardianProtocol.K_OFFLINE_EVENTS_JSON).orEmpty()
        val items = runCatching { GuardianOfflineIncident.decodeList(json) }.getOrDefault(emptyList())
        val added = GuardianPolicyMirror.enqueueOffline(c, items)
        if (added > 0) runCatching { ProactiveRuntime.flushGuardianOfflineSoon() }
        return ok().apply {
            putInt(GuardianProtocol.K_ACCEPTED_COUNT, added)
        }
    }

    private fun ok() = Bundle().apply { putBoolean(GuardianProtocol.K_OK, true) }
    private fun err(msg: String) = Bundle().apply {
        putBoolean(GuardianProtocol.K_OK, false)
        putString(GuardianProtocol.K_ERROR, msg.ifBlank { "unknown_error" })
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "FishHealthProvider"
    }
}
