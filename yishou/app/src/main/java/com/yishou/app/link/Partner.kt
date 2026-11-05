package com.yishou.app.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import java.security.MessageDigest

/**
 * 和「总控」互相连接时，认对方的包名和签名证书。
 *
 * 两个应用都用仓库里固定的调试签名打包。这里写死对方证书的 SHA-256：
 * 只有真正的总控能读一手的状态、给一手派任务；别的应用就算包名相同也连不上。
 */
object Partner {
    const val ZONGKONG = "com.zongkong.app"

    /** 总控 zongkong/app/debug.keystore 的证书 SHA-256。 */
    private const val ZONGKONG_CERT = "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"

    /** 当前这次跨应用调用是不是总控发来的。 */
    fun callerIsZongkong(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        val pkgs = context.packageManager.getPackagesForUid(uid) ?: return false
        return pkgs.any { it == ZONGKONG && signedBy(context, it, ZONGKONG_CERT) }
    }

    /** 手机上装的总控是不是我们自己打包的那个（调用它之前先认一下）。 */
    fun zongkongGenuine(context: Context): Boolean = signedBy(context, ZONGKONG, ZONGKONG_CERT)

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
