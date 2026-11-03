package com.dshdriver.core

/** Only explicitly selected packages can be blocked. Platform/IME/self packages take priority. */
class BlockRules(
    private val blocked: Set<String>,
    private val grants: Map<String, Long> = emptyMap(),
    private val self: String = "com.dshdriver.app",
    private val inputMethods: Set<String> = emptySet(),
    private val systemUi: Set<String> = setOf("android", "com.android.systemui", "com.android.settings"),
) {
    enum class Decision { Block, Allow }
    fun decide(pkg: String?, now: Long): Decision = when {
        pkg.isNullOrBlank() || pkg == self || pkg in inputMethods || pkg in systemUi -> Decision.Allow
        pkg !in blocked || (grants[pkg] ?: Long.MIN_VALUE) > now -> Decision.Allow
        else -> Decision.Block
    }
}
