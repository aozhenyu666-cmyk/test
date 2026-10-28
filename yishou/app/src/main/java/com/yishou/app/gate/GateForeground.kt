package com.yishou.app.gate

/** 系统浮层不代表用户换了应用，否则到期那一下会查错对象。 */
class GateForeground {
    var current: String? = null; private set
    private var observed: String? = null
    val canEnforce: Boolean get() = observed !in REPAIR_PACKAGES
    fun update(pkg: String, transientSystem: Boolean) {
        observed = pkg
        if (!transientSystem) current = pkg
    }

    private companion object {
        val REPAIR_PACKAGES = setOf(
            "com.android.settings", "com.vivo.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
    }
}
