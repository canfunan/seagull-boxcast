package com.seagull.boxcast.crypto

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * 盒子认证 tag 计算（复刻 BoxConnectClient.verifyBoxTag）。
 *
 * 逆向实证（libencryption.so，arm64，带 DWARF；rizin 反汇编 + 行号映射）：
 *   preMd5 = type + "_" + mac + "_" + active + "_" + randomvalue
 *   verifyCode = getVerifyCode(context, preMd5)
 *   isTagMatch = tag.equals(verifyCode)
 *
 * getVerifyCode 内部（encryption.cpp:33-76 全实锤）：
 *   1. GetStringUTFChars(input) → std::string s
 *   2. s.append("CARLIFE")                    ← 盐，0x2d8a3，encryption.cpp:72
 *   3. MD5(s).toStr() → 小写 hex              ← HEX_NUMBERS="0123456789abcdef" @0x2d930
 *   4. NewStringUTF(hex) → 返回
 *   附加：Context→PackageManager→GET_SIGNATURES→strcmp 准入校验（encryption.cpp:67）——
 *         **调用方 APK 签名不符则拒算**。所以我们自己的 APK 调 native so 会被拒，
 *         但算法已逆出，纯 Java 离线算 tag 即可，无需 native。
 *
 * 因此本工程直接走纯算法：verifyCode = md5_hex_lower(input + "CARLIFE")。
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
     * 纯算法实现（libencryption.so 实证）：
     * verifyCode = md5_hex_lower(input + "CARLIFE")
     */
    fun fallback(input: String): String = md5Hex(input + SALT)

    /** 盐（encryption.cpp:72 append 的常量，0x2d8a3） */
    const val SALT = "CARLIFE"

    /**
     * 联调自检：已知一次真实 Hello（tag 已知）时反查算法。
     * 命中 "md5-hex-salted" 即与逆向实证一致。
     */
    fun matchKnown(input: String, expectedTag: String): String? {
        val candidates = mapOf(
            "md5-hex-salted" to md5Hex(input + SALT),
            "md5-hex-raw" to md5Hex(input),
            "md5-b64-salted" to md5B64(input + SALT),
            "sha1-hex-salted" to shaHex(input + SALT, "SHA-1"),
            "sha256-hex-salted" to shaHex(input + SALT, "SHA-256"),
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
