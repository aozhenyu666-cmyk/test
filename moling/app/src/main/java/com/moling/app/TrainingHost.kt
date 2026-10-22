package com.moling.app

import android.content.Context
import android.content.pm.PackageManager
import com.moling.core.redeem.RedeemState
import com.moling.core.redeem.TrainingRedeemClient
import com.moling.core.redeem.UnlockPolicyView
import com.moling.core.workspace.PortResult

/**
 * 以思代锁（A3）：墨灵侧唯一的训练兑换客户端。
 *
 * 状态只在内存里：题目、判分、额度、通行令都由总控出。墨灵重启后重新向总控要策略 / 回执，
 * 不自己记放行，也不本地补发一分钟。
 */
object TrainingHost {
    /** 和今天页共用同一份客户端状态（题目、回执、上一次通行）。 */
    val client: TrainingRedeemClient by lazy { TrainingRedeemClient(ZkBridge.gateV2) }

    /** 总控当前生效的训练策略；连不上 / 旧总控 / 没装都走 PortResult.Failed。 */
    fun policy(): PortResult<UnlockPolicyView> = ZkBridge.gateV2.policy()

    fun snapshot(): RedeemState = client.snapshot()

    /** 应用名；没装 / 取不到就用包名。 */
    fun label(context: Context, pkg: String): String = try {
        val pm: PackageManager = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
