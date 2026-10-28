package com.yishou.app.system

import com.yishou.app.BuildConfig

/**
 * 这是哪一条路：主线（一手）还是岔路（探索版本，一条一条试）。
 * 岔路和主线装在同一台手机上互不影响（包名不同、数据分开）。
 */
object Variant {
    /** 岔路：捉虫 / 变条件、先喷后砍、回响队列、入口不放水、不可逆动作前停 2 秒 */
    val chalu: Boolean = BuildConfig.CHALU

    /** “岔路 1”；主线为空 */
    val name: String = BuildConfig.CHALU_NAME
}
