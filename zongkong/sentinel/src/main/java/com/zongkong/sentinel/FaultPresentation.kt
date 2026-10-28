package com.zongkong.sentinel

/** 一次连续故障只主动打开一次；息屏和修复设置页不会消耗这次提示。 */
class FaultPresentation {
    private var presented = false
    fun shouldPresent(fault: Boolean, allowed: Boolean, interactive: Boolean): Boolean {
        if (!fault || !allowed) {
            presented = false
            return false
        }
        if (!interactive || presented) return false
        presented = true
        return true
    }
}

