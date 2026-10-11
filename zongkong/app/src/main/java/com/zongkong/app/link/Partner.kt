package com.zongkong.app.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import com.zongkong.core.Link
import java.security.MessageDigest

/**
 * 和「一手」互相连接时，认对方的包名和签名证书。
 * 两个应用都用仓库里固定的调试签名打包，这里写死一手证书的 SHA-256：
 * 只有真正的一手能读总控的状态；别的应用就算包名相同也连不上。
 */
object Partner {
    /** 一手 yishou/app/debug.keystore 的证书 SHA-256。 */
    private const val YISHOU_CERT = "EDCE45B1C655885E106D3A08A76D169E8A50CFA79F364415F8AC997B44D87BCE"

    fun callerIsYishou(context: Context): Boolean {
        val pkgs = context.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return false
        return pkgs.any { it == Link.YISHOU && signedBy(context, it, YISHOU_CERT) }
    }

    fun yishouGenuine(context: Context): Boolean = signedBy(context, Link.YISHOU, YISHOU_CERT)

    fun installed(context: Context, pkg: String = Link.YISHOU): Boolean = try {
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
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty().any { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).contentEquals(hex(sha256Hex))
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
