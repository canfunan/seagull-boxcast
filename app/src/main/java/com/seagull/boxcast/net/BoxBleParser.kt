package com.seagull.boxcast.net

import android.util.Base64
import com.seagull.boxcast.crypto.BoxCrypto
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 盒子 BLE 广播解析（复刻 vivo 版 WifiDirectDiscoverManager.parseSsids，:415-451）。
 *
 * 实证结构（Manufacturer Specific Data, AD type 0xFF）：
 *   scanRecord 里有多个 BLE AD structure（len/type/value 三段式）
 *   第 1 个 type=0xFF 段：跳过（厂商魔数）
 *   第 2 个 type=0xFF 段：value = [16 字节密文][剩余明文]
 *     plain = AES_ECB_decrypt(value 前 16 字节) XOR aesKey()  // 密钥 H22/emBpCuspF1DYYaJjxQ==
 *     result = (new String(plain) + new String(value 其余字节)).split(",")
 *     → [ssid, password, frequency?]
 *
 * 用途：BLE 一扫即得热点凭据 → WiFi P2P/热点直连，无需用户输密码（"秒连"原理）。
 */
object BoxBleParser {

    data class BoxCredentials(val ssid: String, val password: String, val frequency: String?)

    /**
     * 解析 BLE ScanRecord 原始字节。
     * @return 热点凭据；非盒子广播返回 null
     */
    fun parse(scanRecord: ByteArray): BoxCredentials? {
        var i = 0
        var ffCount = 0
        while (i < scanRecord.size - 1) {
            // 权威 parseSsids:421,448 用**有符号** byte b（AD 段长≥128 时符号扩展）——
            // 原 and 0xFF 无符号化与之行为相反
            val len = scanRecord[i].toInt()
            if (len == 0) return null
            val typeIdx = i + 1
            if (typeIdx >= scanRecord.size) return null
            val type = scanRecord[typeIdx].toInt() and 0xFF
            if (type == 0xFF) {   // Manufacturer Specific Data
                val valueEnd = minOf(typeIdx + len, scanRecord.size)
                val value = scanRecord.copyOfRange(typeIdx + 1, valueEnd)
                // 权威 ≥16 即解密（原 >16 会在恰好 16B 时漏解）
                if (ffCount == 1 && value.size >= 16) {
                    return decodeSecondFf(value)
                }
                ffCount++
            }
            i = len + typeIdx
        }
        return null
    }

    private fun decodeSecondFf(value: ByteArray): BoxCredentials? {
        return runCatching {
            // AES/ECB/NoPadding 解密前 16 字节（密钥走白盒钩子，默认原始 base64）
            val keyBytes = BoxCrypto.aesKey()
            val cipher = Cipher.getInstance(BoxCrypto.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val dec = cipher.doFinal(value.copyOfRange(0, 16))
            // 解密结果再 XOR aesKey——**只 XOR min(16, keyBytes.size) 字节**
            // （原 k%size 环绕与权威不符；16B 密文场景巧合一致）
            val xorLen = minOf(16, keyBytes.size)
            for (k in 0 until xorLen) {
                dec[k] = (dec[k].toInt() xor keyBytes[k].toInt()).toByte()
            }
            val head = String(dec, Charsets.UTF_8)
            val tail = String(value.copyOfRange(16, value.size), Charsets.UTF_8)
            val parts = (head + tail).split(",")
            if (parts.size >= 2) {
                BoxCredentials(
                    ssid = parts[0],
                    password = parts[1],
                    frequency = parts.getOrNull(2),
                )
            } else null
        }.getOrNull()
    }

    /** BLE 服务 UUID（BleBluetoothDiscover 实证）——盒子广播过滤用 */
    const val BOX_SERVICE_UUID = "c62857aa-6ca1-4ac4-8842-3ca71aef6765"

    /** BLE 广播名匹配关键词（TARGET_NAME="box" 实证，小写包含） */
    const val BOX_NAME_KEYWORD = "box"
}
