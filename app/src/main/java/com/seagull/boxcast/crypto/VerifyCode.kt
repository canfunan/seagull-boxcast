package com.seagull.boxcast.crypto

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * 盒子认证 tag 计算（复刻 BoxConnectClient.verifyBoxTag）。
 *
 * 逆向实证：
 *   preMd5 = type + "_" + mac + "_" + active + "_" + randomvalue
 *   verifyCode = EncryptionUtils.getVerifyCode(context, preMd5)   ← native(libencryption.so)
 *   isTagMatch = tag.equals(verifyCode)
 *
 * getVerifyCode 为 System.loadLibrary("encryption") 的 native 方法，1.4MB so。
 * 两条路径：
 *   1) [Native]：把 vivo 版 APK 里的 libencryption.so 放到 app/src/main/jniLibs/arm64-v8a/
 *      并放开 build.gradle.kts 中 jniLibs 配置——本类自动加载并调用（最保真）。
 *   2) [Fallback]：纯算法候选（MD5/SHA-1/SHA-256 的 hex / base64 变体）。
 *      因为输入拼接完全已知，抓包对比一次即可锁定是哪一种。
 */
object VerifyCode {

    private const val TAG = "VerifyCode"

    /** native 路径：与 com.baidu.encryption.EncryptionUtils 相同的 JNI 签名 */
    @Volatile
    private var nativeReady = false

    private var nativeLoaded = false

    external fun getVerifyCode(ctx: Context, input: String): String

    @Synchronized
    private fun tryLoadNative(): Boolean {
        if (nativeLoaded) return nativeReady
        nativeLoaded = true
        nativeReady = runCatching {
            System.loadLibrary("encryption")
            Log.i(TAG, "libencryption.so loaded, native verify path active")
            true
        }.getOrDefault(false)
        return nativeReady
    }

    /**
     * 计算盒子认证码。
     * @param type        盒子类型（yundian/junyong/liaoyuan/suding…）
     * @param mac         盒子 MAC
     * @param active      Hello.active
     * @param randomvalue Hello.randomvalue
     * @return 认证码；失败返回 null
     */
    fun compute(ctx: Context, type: String, mac: String, active: Int, randomvalue: Int): String? {
        val input = "${type}_${mac}_${active}_${randomvalue}"
        return if (tryLoadNative()) {
            runCatching { getVerifyCode(ctx.applicationContext, input) }.getOrNull()
                ?: fallback(input)
        } else {
            fallback(input)
        }
    }

    /**
     * 纯算法回退：输出 hex 与 base64 两种常见形态。
     * TODO-CALIBRATE: 抓到一次真实 Hello（盒子 tag 已知）后，
     * 用 [matchKnown] 锁定算法，然后把候选收敛成一个。
     */
    fun fallback(input: String): String {
        // 默认给 MD5-hex（车载盒子最常见）；实测不符再切
        return md5Hex(input)
    }

    /** 已知一次真实 tag 时，反查是哪种算法（联调工具） */
    fun matchKnown(input: String, expectedTag: String): String? {
        val candidates = mapOf(
            "md5-hex" to md5Hex(input),
            "md5-base64" to md5B64(input),
            "sha1-hex" to shaHex(input, "SHA-1"),
            "sha1-base64" to shaB64(input, "SHA-1"),
            "sha256-hex" to shaHex(input, "SHA-256"),
            "sha256-base64" to shaB64(input, "SHA-256"),
        )
        return candidates.entries.firstOrNull { it.value == expectedTag }?.key
    }

    private fun digest(input: String, algo: String): ByteArray =
        MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    fun md5Hex(s: String) = hex(digest(s, "MD5"))
    fun md5B64(s: String) = b64(digest(s, "MD5"))
    fun shaHex(s: String, algo: String) = hex(digest(s, algo))
    fun shaB64(s: String, algo: String) = b64(digest(s, algo))
}
