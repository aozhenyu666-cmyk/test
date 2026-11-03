package com.zongkong.app.system

/** Android 同一组件允许完整类名和 .短类名两种写法；不改动其他服务的原始字符串。 */
object EnabledServiceList {
    private fun full(value: String): String {
        val parts = value.split('/', limit = 2)
        return if (parts.size == 2 && parts[1].startsWith('.')) parts[0] + "/" + parts[0] + parts[1] else value
    }
    fun append(current: String, component: String): String {
        val entries = current.split(':').filter { it.isNotBlank() }
        return if (entries.any { full(it).equals(full(component), true) }) current else (entries + component).joinToString(":")
    }
}
