package com.zongkong.sentinel

/** 哨兵和总控之间的约定（总控那边 com.zongkong.app.guard.SentinelClient 写的是同一套）。 */
object Proto {
    const val MAIN_PKG = "com.zongkong.app"
    const val MAIN_GUARD = "com.zongkong.app/com.zongkong.app.guard.GuardService"
    /** 总控开给哨兵的口子：叫醒总控的进程。 */
    const val MAIN_WAKE_URI = "content://com.zongkong.app.sentinel"
    const val AUTH = "com.zongkong.sentinel.link"

    const val STATE = "state"
    const val PING = "ping"
    const val WAKE = "wake"

    const val AT = "at"
    const val STRICT = "strict"
    const val FREE_UNTIL = "freeUntil"
    const val BLOCKED = "blocked"
    const val RUNNING = "running"
    const val BEAT = "beat"
    const val TAKEOVERS = "takeovers"
    const val LAST_TAKEOVER = "lastTakeover"
    const val CAN_WRITE = "canWrite"
    const val VERSION = "version"
    const val LOG = "log"
    const val ERROR = "error"
    /** 解除闸门生效到几点（墙上时间毫秒）；0 = 没有解除。总控据此让路。 */
    const val RELEASED_UNTIL = "releasedUntil"
}
