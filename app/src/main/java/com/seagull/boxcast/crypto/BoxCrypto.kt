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

    /**
     * 白盒变换钩子：so 层 libbaiduprotect_sec.getWhiteKey 的实现（Frida 提取后注入）。
     * 默认 null → 走权威路径的近似（getWhiteKey(CARLIFE_BOX_KEY).getBytes(UTF_8)）。
     *
     * 2026-10-29 已提取真 key（wbharness dalvikvm64 跑真实 whitebox-encrypt.key/decrypt.key）：
     *   getWhiteKey("H22/emBpCuspF1DYYaJjxQ==") = "53fe2343-51dc-41"（16B ASCII）
     * 交叉验证：盒子固件 v1.0.1 libtransbox.so .rodata@0x1E69DC 明文硬编码同一把，
     * 且用其对 transbox_log.dr 真实配网流量复算 AES-128-ECB 全 MATCH。
     */
    var whiteKeyHook: ((String) -> String)? = { "53fe2343-51dc-41" }

    /**
     * 密钥派生——**权威路径**（AESManagerBox.java:23,35 实证）：
     *   getWhiteKey("H22/emBpCuspF1DYYaJjxQ==").getBytes(UTF_8)
     * 白盒变换在 so 层（Java 不可见）→ 默认用入参字符串自身 UTF_8 字节（24B/AES-192）；
     * hook 提取到真 key 后自动覆盖。原 Base64 解码（16B/AES-128）与权威不符。
     */
    private val keyBytes: ByteArray by lazy {
        val raw = whiteKeyHook?.invoke(BOX_KEY_B64) ?: BOX_KEY_B64
        raw.toByteArray(Charsets.UTF_8)
    }

    /**
     * Cipher 非线程安全：原版 ConnectSocket 收/发各持一个 AESManager。
     * 本工程多线程调用（编码线程/采集线程/心跳协程）→ ThreadLocal 每线程独立实例。
     */
    private val encryptCipher = object : ThreadLocal<Cipher>() {
        override fun initialValue() = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
    }

    private val decryptCipher = object : ThreadLocal<Cipher>() {
        override fun initialValue() = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
        }
    }

    /** AES 密钥（白盒输出 UTF_8 字节；hook 优先） */
    fun aesKey(): ByteArray = keyBytes.copyOf()

    fun encrypt(data: ByteArray, len: Int = data.size): ByteArray? =
        runCatching { encryptCipher.get()!!.doFinal(data, 0, len) }.getOrNull()

    fun decrypt(data: ByteArray, len: Int = data.size): ByteArray? =
        runCatching { decryptCipher.get()!!.doFinal(data, 0, len) }.getOrNull()

    /** 标准握手加密（AES/ECB/PKCS5Padding——2026 AesEncryptor.java:41-44 权威） */
    fun encryptWithKey(key: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(data)
    }.getOrNull()

    fun decryptWithKey(key: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        c.doFinal(data)
    }.getOrNull()

    /** 盒子链路专用（AES/ECB/NoPadding + 白盒 key——AESManagerBox.java:16-24 权威） */
    fun encryptNoPadding(key: ByteArray, data: ByteArray): ByteArray? =
        encrypt(data, data.size)   // ThreadLocal NoPadding 实例

    fun decryptNoPadding(key: ByteArray, data: ByteArray): ByteArray? =
        decrypt(data, data.size)
}
