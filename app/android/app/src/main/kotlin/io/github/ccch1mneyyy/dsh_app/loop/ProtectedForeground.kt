package io.github.ccch1mneyyy.dsh_app.loop

import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages

/**
 * Pages proactive surfaces must never cover, judged from the foreground package alone.
 *
 * DSH's own driver protects Settings only on certain pages (accessibility, permissions…), which it
 * recognises by reading the page title. The proactive kernel reads no page content, so it cannot
 * tell those pages apart: it treats the whole Settings app as protected, plus every package
 * [ProtectedPages] protects outright (installers, permission controllers, security centres).
 */
object ProtectedForeground {
    fun isProtected(pkg: String?): Boolean =
        pkg != null && (pkg in ProtectedPages.packages || pkg in ProtectedPages.settingsPackages)
}
