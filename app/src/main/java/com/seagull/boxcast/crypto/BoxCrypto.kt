package com.seagull.boxcast.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 盒子链路 AES（复刻 com.baidu.carlife.core.connect.encrypt.AESManagerBox）。
 *
 * 逆向实证：
 *   CARLIFE_BOX_KEY = "H22/emBpCuspF1DYYaJjxQ=="   （硬编码 base64）
 *   Cipher: AES/ECB/NoPadding
 *   密钥经 CommonMsgTransmit.getWhiteKey() 白盒处理后取 UTF-8 字节
 *
 * 注意：getWhiteKey 是 native 白盒变换（libbaiduprotect_sec.so）。
 * 这里默认直接用原始 base64 解码值；若实测盒子侧期望白盒输出，
 * 把白盒 so 一起打包并替换 [whiteKey] 实现。
 */
object BoxCrypto {

    const val BOX_KEY_B64 = "H22/emBpCuspF1DYYaJjxQ=="
    const val TRANSFORMATION = "AES/ECB/NoPadding"

    /** 白盒变换钩子：默认恒等。接 so 时在此调用 native getWhiteKey。 */
    var whiteKeyHook: ((String) -> String)? = null

    private val keyBytes: ByteArray by lazy {
        val raw = whiteKeyHook?.invoke(BOX_KEY_B64) ?: BOX_KEY_B64
        Base64.decode(raw, Base64.NO_WRAP)
    }

    private val encryptCipher: Cipher by lazy {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
    }

    private val decryptCipher: Cipher by lazy {
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
    }

    /** AES 密钥（16 字节） */
    fun aesKey(): ByteArray = keyBytes.copyOf()

    fun encrypt(data: ByteArray, len: Int = data.size): ByteArray? =
        runCatching { encryptCipher.doFinal(data, 0, len) }.getOrNull()

    fun decrypt(data: ByteArray, len: Int = data.size): ByteArray? =
        runCatching { decryptCipher.doFinal(data, 0, len) }.getOrNull()
}
