package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * R4-0: did this window event really switch to another app?
 *
 * A window event fires for *every* window that comes to the front, including the ones that only
 * cover the app still underneath: the IME, a permission dialog, the installer, the system UI, and
 * DSH's own pages (the intercept page sits in the same task). Treating those as "he switched away"
 * made the 连麦舱 lock the keyboard and throw him out of Gemini Live the moment it opened.
 *
 * Pure Kotlin (no Android imports) so the decision is unit-tested on the JVM. The IME package list
 * comes from `InputMethodManager`; the name rules are only a fallback for an IME we have not seen.
 */
object FrontChange {
    /** Always a layer, never an app. */
    private val LAYER_PKGS = setOf("android", "com.android.systemui")

    /** Fallback for IMEs and dialogs whose package name we did not get from the system. */
    private val LAYER_WORDS = listOf("inputmethod", "permissioncontroller", "packageinstaller")
    private val LAYER_SUFFIXES = listOf(".ime", ".input")

    /** IME packages as reported by the system; filled once when the service connects. */
    @Volatile
    private var imePkgs: Set<String> = emptySet()

    /** DSH itself, including the `.fish` build and its intercept page. */
    @Volatile
    private var selfPkgs: Set<String> = emptySet()

    fun setIme(pkgs: Set<String>) {
        imePkgs = pkgs
    }

    fun setSelf(pkgs: Set<String>) {
        selfPkgs = pkgs
    }

    /** The IMEs the system reported, for callers that judge with their own list (SessionPolicy). */
    fun imePackages(): Set<String> = imePkgs

    /** A welcome side effect: a new IME is picked up from the name rules without a refresh. */
    fun isLayer(pkg: String, ime: Collection<String>, self: Collection<String>): Boolean {
        if (pkg in LAYER_PKGS || pkg in ime || pkg in self) return true
        val lower = pkg.lowercase()
        if (LAYER_WORDS.any { lower.contains(it) }) return true
        return LAYER_SUFFIXES.any { lower.endsWith(it) }
    }

    /** Same question against the lists the phone has already registered. */
    fun isLayer(pkg: String): Boolean = isLayer(pkg, imePkgs, selfPkgs)

    /**
     * For time accounting (NowFeed): the IME and system dialogs are layers, but DSH itself is a real
     * place he went. Counting the lock page or a DSH chat as "still in the game" would book that
     * time as entertainment and could read as defiance.
     */
    fun isOverlay(pkg: String, ime: Collection<String>): Boolean = isLayer(pkg, ime, emptySet())

    fun isOverlay(pkg: String): Boolean = isOverlay(pkg, imePkgs)

    /** True only for a real app switch. null / blank / a layer on top are all "no switch". */
    fun switched(pkg: String?, ime: Collection<String>, self: Collection<String>): Boolean =
        !pkg.isNullOrBlank() && !isLayer(pkg, ime, self)

    fun switched(pkg: String?): Boolean = switched(pkg, imePkgs, selfPkgs)
}
