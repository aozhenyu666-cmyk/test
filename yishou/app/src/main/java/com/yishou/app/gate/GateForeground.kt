package com.yishou.app.gate

/** 系统浮层不代表用户换了应用，否则到期那一下会查错对象。 */
class GateForeground {
    var current: String? = null; private set
    fun update(pkg: String, transientSystem: Boolean) { if (!transientSystem) current = pkg }
}
