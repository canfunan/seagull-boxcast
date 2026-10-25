package com.seagull.boxcast.crypto

import android.util.Base64
import com.seagull.boxcast.frame.CarlifeFrame
import com.seagull.boxcast.net.BoxConnection
import com.seagull.boxcast.proto.Wire
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 加密握手（复刻 EncryptSetupManager + RSAManager 实证流程）。
 *
 * 前提：AMIS 配置 CONTENT_ENCRYPTION 开启（mEncryptConfigSwitch）。
 * 盒子（耘电 1024xx + Hello tag）不走这套；**标准 HU 会协商加密**。
 *
 * 流程（EncryptSetupManager.exec/handler 实证）：
 *   1. MD → HU: MD_RSA_PUBLIC_KEY_REQUEST(65642)
 *   2. HU → MD: HU_RSA_PUBLIC_KEY_RESPONSE(98411) {rsaPublicKey: Base64 X509}
 *   3. MD: seed = UUID.random().substring(0,16)          ← EncryptConfig.getAesSeed 实证
 *          aesKey = Base64(RSA/ECB/PKCS1Padding(seed, HU公钥))
 *      MD → HU: MD_AES_KEY_SEND_REQUEST(65644) {aesKey}
 *   4. HU → MD: HU_AES_REC_RESPONSE(98413)
 *   5. 启用：之后**所有 CmdMessage 的 data** 用 seed 做 AES/ECB 加密，
 *      length 改为密文长度（ConnectSocket.writeData 实证）
 */
class EncryptHandshake(private val conn: BoxConnection) {

    companion object {
        /** CarlifeHuRsaPublicKeyResponse{rsaPublicKey} / CarlifeMdAesKeyRequest{aesKey} */
        fun encodeRsaResponse(key: String): ByteArray = Wire.Writer().string(1, key).toByteArray()
        fun encodeAesKey(key: String): ByteArray = Wire.Writer().string(1, key).toByteArray()
        fun decodeSingleString(data: ByteArray): String = Wire.Reader(data).run {
            var s = ""
            while (!atEnd()) {
                val (f, w) = readTag()
                if (f == 1 && w == Wire.WIRE_LEN) s = readString() else skip(w)
            }
            s
        }
    }

    @Volatile var enabled = false
        private set

    /** AES seed（UUID 前 16 位；加密/解密共用） */
    @Volatile var seed: String = ""
        private set

    /** 配置开关（AMIS CONTENT_ENCRYPTION；默认 false，盒子场景不开） */
    @Volatile var configSwitch = false

    var onEnabled: (() -> Unit)? = null

    /** 启动握手（configSwitch=true 时调用） */
    fun start() {
        if (!configSwitch || enabled) return
        conn.sendRaw(CarlifeFrame.CmdId.MSG_CMD_MD_RSA_PUBLIC_KEY_REQUEST, ByteArray(0))
    }

    /** 收 HU_RSA_PUBLIC_KEY_RESPONSE(98411) */
    fun onRsaResponse(data: ByteArray) {
        val keyB64 = decodeSingleString(data)
        runCatching {
            val kf = KeyFactory.getInstance("RSA")
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(keyB64, Base64.NO_WRAP)))
            seed = UUID.randomUUID().toString().substring(0, 16)   // EncryptConfig 实证
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pub)
            val enc = Base64.encodeToString(cipher.doFinal(seed.toByteArray()), Base64.NO_WRAP)
            conn.sendRaw(CarlifeFrame.CmdId.MSG_CMD_MD_AES_KEY_SEND_REQUEST, encodeAesKey(enc))
        }
    }

    /** 收 HU_AES_REC_RESPONSE(98413)：启用加密 */
    fun onAesRecResponse() {
        enabled = configSwitch && seed.isNotEmpty()
        if (enabled) onEnabled?.invoke()
    }

    /** 断连重置（EncryptSetupManager.onDisconnection 实证） */
    fun onDisconnection() {
        enabled = false
        seed = ""
    }

    /**
     * 发送前加密（AES/ECB/NoPadding 对齐 BoxCrypto）。
     * **非 16 倍数不再静默回落明文**（安全降级）：加密启用后 payload 非 16 倍数
     * 说明协议/密钥有偏差——发明文对端必然解失败且无提示，改为抛错由上层断链重协商。
     */
    fun encryptIfEnabled(data: ByteArray): ByteArray {
        if (!enabled || data.isEmpty()) return data
        return BoxCrypto.encryptWithKey(seed.toByteArray(Charsets.UTF_8), data)
            ?: throw IllegalStateException("encrypt fail (size=${data.size})")
    }

    /** 接收后解密（同上：失败抛错，不静默回落） */
    fun decryptIfEnabled(data: ByteArray): ByteArray {
        if (!enabled || data.isEmpty()) return data
        return BoxCrypto.decryptWithKey(seed.toByteArray(Charsets.UTF_8), data)
            ?: throw IllegalStateException("decrypt fail (size=${data.size})")
    }
}
