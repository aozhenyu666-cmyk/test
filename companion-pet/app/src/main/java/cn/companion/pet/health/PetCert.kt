package cn.companion.pet.health

/*
 * 证书摘要常量（规范 §3.1/§9：发布时记录 APK 版本、证书指纹与协议版本）。
 *
 * 同桌用的是仓库里**自己**那把调试钥匙（keystore/tongzhuo-debug.jks），
 * 与总控 / 一手 / 哨兵那把 debug.keystore 不是同一把，所以这里不能抄别的产品。
 * 取值方式（实测，不猜）：
 *   apksigner.bat verify --print-certs app-debug.apk
 * 验证日期与原始输出见 companion-pet/PET-V2-NOTES.md。
 *
 * 注意：调试签名不是抗伪造方案，只是让四个 APK 互认时有个具体的比对对象。
 */
object PetCert {
    /** 同桌 debug APK 的签名证书 SHA-256（小写 hex，64 位）。实测值，不是抄来的。 */
    const val SELF_SHA256 = "9f3a524c66974aeb04045f498f83b5970589f9fba3ac3772e95c26e3524695e0"
}
