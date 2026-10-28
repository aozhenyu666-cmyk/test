package com.zongkong.sentinel.watch

/**
 * 可信证书清单（报告 §9 / 规范 §3.1）—— **纯 Kotlin，无 Android 依赖**，方便 JVM 单测守格式。
 *
 * 为什么单独成文件：这份常量一旦写错一位 hex，`equals` 就永远 false，客户端 register 会被
 * 静默拒掉，把 T02 整条链路堵死（已发生过一次：63 位 vs 64 位）。所以：
 *  - 每个值必须是 **64 位 hex**，由 `TrustedCertTest` 逐条断言；
 *  - `Auth.check` 里也做一次格式校验，格式不合法时给出明确错误串，而不是「莫名被拒」。
 *
 * 四个 APK 并不是同一把签名，必须逐包登记，不能假设同源即同签。
 * 调试签名**不是**抗伪造方案：这里挡的是「别的应用冒充客户端」，不是「有 root 的人」。
 *
 * 核对状态（本机 2026-10-25）：
 *  - `com.zongkong.app`：apksigner 实测 `zongkong/app/build/outputs/apk/debug/app-debug.apk`，
 *    与哨兵 `sentinel-debug.apk` 同一把（哨兵用 `../app/debug.keystore`）。**已验证。**
 *  - `com.yishou.app`：与本机 yishou debug APK 实测一致。**已验证。**
 *  - `cn.companion.pet`：来自仓库提交的 `keystore/tongzhuo-debug.jks`（keytool），
 *    **待用 apksigner 对构建出的同桌 APK 实测确认**（见 SENTINEL-V2-NOTES.md）。
 */
object TrustedCerts {

    /** SHA-256 十六进制摘要的长度（32 字节 → 64 个 hex 字符）。 */
    const val HEX_LEN = 64

    /** 三个客户端包名 → 证书 SHA-256（大写 hex）。 */
    val MAP: Map<String, String> = mapOf(
        // 总控：zongkong/app/debug.keystore（与哨兵同一把）
        "com.zongkong.app" to "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9",
        // 一手：yishou/app/debug.keystore
        "com.yishou.app" to "EDCE45B1C655885E106D3A08A76D169E8A50CFA79F364415F8AC997B44D87BCE",
        // 同桌：keystore/tongzhuo-debug.jks（待 apksigner 实测确认）
        "cn.companion.pet" to "9F3A524C66974AEB04045F498F83B5970589F9FBA3AC3772E95C26E3524695E0",
    )

    fun isWellFormed(sha: String): Boolean =
        sha.length == HEX_LEN && sha.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    /** 一条都没写错才算清单可用；写错时返回有问题的条目说明（给日志 / 启动自检用）。 */
    fun validate(): List<String> = MAP.mapNotNull { (pkg, sha) ->
        when {
            sha.length != HEX_LEN -> "$pkg 摘要长度 ${sha.length}，应为 $HEX_LEN"
            !isWellFormed(sha) -> "$pkg 摘要含非 hex 字符"
            else -> null
        }
    }
}
