package com.yishou.app.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import java.security.MessageDigest

/**
 * 和「总控」「哨兵」互相连接时，认对方的包名和签名证书。
 *
 * 三个应用都用各自仓库里固定的调试签名打包。这里写死对方证书的 SHA-256：
 * 只有真正的总控能读一手的状态、给一手派任务；只有真正的哨兵能给一手发健康挑战。
 * 别的应用就算包名相同也连不上。
 *
 * 注意（规范 §9）：调试签名不是抗伪造方案，这里挡的是「别的应用冒充」，不是「有 root 的人」。
 */
object Partner {
    const val ZONGKONG = "com.zongkong.app"
    const val SENTINEL = "com.zongkong.sentinel"

    /** 总控 zongkong/app/debug.keystore 的证书 SHA-256。 */
    private const val ZONGKONG_CERT = "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"

    /** 哨兵 zongkong/sentinel 用的也是同一把签名（sentinel/build.gradle.kts 指向 ../app/debug.keystore）。 */
    private const val SENTINEL_CERT = "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"

    /** 一手自己的证书（哨兵的可信清单里登记的就是这个）。 */
    const val YISHOU_CERT = "EDCE45B1C655885E106D3A08A76D169E8A50CFA79F364415F8AC997B44D87BCE"

    /** 当前这次跨应用调用是不是总控发来的。 */
    fun callerIsZongkong(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return false
        return pkgs.any { it == ZONGKONG && signedBy(context, it, ZONGKONG_CERT) }
    }

    /**
     * 当前这次跨应用调用是不是哨兵发来的（规范 §3.2：客户端 provider 只接受哨兵的签名）。
     * 只认包名 + 证书两道，不看消息体里自称是谁。
     */
    fun callerIsSentinel(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return false
        return pkgs.any { it == SENTINEL && signedBy(context, it, SENTINEL_CERT) }
    }

    /** 手机上装的总控是不是我们自己打包的那个（调用它之前先认一下）。 */
    fun zongkongGenuine(context: Context): Boolean = signedBy(context, ZONGKONG, ZONGKONG_CERT)

    /** 手机上装的哨兵是不是我们自己打包的那个。 */
    fun sentinelGenuine(context: Context): Boolean = signedBy(context, SENTINEL, SENTINEL_CERT)

    fun installed(context: Context, pkg: String = ZONGKONG): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun signedBy(context: Context, pkg: String, sha256Hex: String): Boolean = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            pm.hasSigningCertificate(pkg, hex(sha256Hex), PackageManager.CERT_INPUT_SHA256)
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            info.signatures.orEmpty().any { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).contentEquals(hex(sha256Hex))
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
