package io.github.ccch1mneyyy.dsh_app.loop.core

/**
 * The one visible DSH surface at a time.
 *
 * Two features can want the screen at the same moment: 主动搭话 shows a page from the coordinator,
 * 娱乐拦截 shows a page because the person just opened a target app. They are two activities with
 * two task affinities, so without a shared slot both can be on screen at once - the person gets two
 * DSH pages stacked, and one of them records an outcome the other never saw.
 *
 * This is deliberately tiny and pure: it decides *who* holds the screen, nothing else. No timer, no
 * queue, no policy - every caller keeps its own book. [owner] names the feature ("proactive",
 * "intercept"); [token] names the surface instance (a coordinator token, an intercept eventId), so
 * a stale release can never free a surface it does not own.
 *
 * **Replacement, not starvation.** Both page activities are `singleInstance`: a newer presentation
 * reuses the same Activity instance and only swaps its token (`onNewIntent`). So "same owner, new
 * token" is not a conflict - it is the *same* feature replacing its own surface, and it must take
 * the slot over (the slot then records the new token). Refusing it would make the new presentation
 * fail as "surface_busy" while the reused Activity is already showing the new content, and the slot
 * would stay pinned to a token nobody will ever release: the feature starves itself. The old token
 * is then *stale*, and the release guard below is what keeps its late `onDestroy` from freeing the
 * replacement.
 *
 * **Never persisted, on purpose.** The slot lives in memory only. A process death takes every DSH
 * page with it (activities die with the process), so after a restart the screen genuinely is free:
 * restoring a persisted holder would lock the screen against a page that no longer exists. Every
 * `recover()` therefore starts from a free slot - and the tests pin that.
 */
class SurfaceSlot {

    private var owner: String? = null
    private var token: String? = null
    private var since: Long = 0L

    /** Who holds the screen now; null when nothing is showing. */
    val holder: String? get() = owner

    /** The surface instance that holds it, for the stale-release guard. */
    val holderToken: String? get() = token

    fun isFree(): Boolean = owner == null

    fun isHeldBy(who: String): Boolean = owner == who

    /**
     * Take the slot for [who]/[token].
     *
     *  * free → taken;
     *  * same owner and same token → true, and nothing moves (a repeated Present for the same
     *    surface is not a second surface);
     *  * same owner, **different** token → a replacement of that feature's own surface: the slot
     *    is handed to [token] and the old token becomes stale (see [release]);
     *  * another owner → false. Two features never share the screen.
     */
    fun claim(who: String, token: String, now: Long): Boolean = when {
        owner == null -> {
            this.owner = who
            this.token = token
            since = now
            true
        }
        owner == who && this.token == token -> true
        owner == who -> {
            // Replacement: the same feature re-presenting itself (singleInstance + onNewIntent).
            this.token = token
            since = now
            true
        }
        else -> false
    }

    /**
     * Give the slot back. Only the holder can release, and only for the instance that took it:
     * a page's late onDestroy must not free the surface its replacement already claimed.
     */
    fun release(who: String, token: String?): Boolean {
        if (owner != who) return false
        if (token != null && this.token != token) return false
        owner = null
        this.token = null
        since = 0L
        return true
    }

    /** Since when the current holder has had it; 0 when free. */
    fun heldSince(): Long = since

    fun clear() {
        owner = null
        token = null
        since = 0L
    }
}

/**
 * The process-wide slot. Android adapters go through this object; the rules themselves stay in
 * [SurfaceSlot] so they can be tested without Android.
 */
object VisibleSurface {
    const val OWNER_PROACTIVE = "proactive"
    const val OWNER_INTERCEPT = "intercept"
    const val OWNER_FOCUS = "focus"

    private val slot = SurfaceSlot()

    fun claim(owner: String, token: String, now: Long = System.currentTimeMillis()): Boolean =
        slot.claim(owner, token, now)

    fun release(owner: String, token: String?): Boolean = slot.release(owner, token)

    fun holder(): String? = slot.holder

    fun holderToken(): String? = slot.holderToken

    fun isHeldBy(owner: String): Boolean = slot.isHeldBy(owner)

    fun isFree(): Boolean = slot.isFree()
}
