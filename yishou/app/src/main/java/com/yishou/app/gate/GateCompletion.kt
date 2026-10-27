package com.yishou.app.gate

import com.yishou.app.judge.Verdict

object GateCompletion {
    fun ready(verdict: Verdict?, triggerStored: Boolean): Boolean =
        verdict?.passed == true && !verdict.unavailable && triggerStored
}
