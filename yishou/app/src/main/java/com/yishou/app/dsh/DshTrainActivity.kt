package com.yishou.app.dsh

import android.app.Activity
import android.os.Bundle
import com.yishou.app.gate.GateActivity

/** 外部只进校验跳板，真正的思考页仍然不导出。 */
class DshTrainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        @Suppress("DEPRECATION")
        val req = if (savedInstanceState == null && intent?.action == DshHandoff.ACTION_TRAIN && extras != null)
            DshHandoff.parseRequest { extras.get(it) } else null
        if (req != null) startActivity(GateActivity.dshIntent(this, req))
        finish()
    }
}
