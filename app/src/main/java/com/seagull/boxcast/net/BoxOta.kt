package com.seagull.boxcast.net

import android.util.Log
import com.seagull.boxcast.proto.BoxCheckUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 盒子 OTA 检查（复刻 BoxUpdateModel + CarlifeBoxCheckUpdateProto）。
 *
 * BoxUpdateModel 五字段：httpUrl / httpType / paramjson / responseKey / otaAddress。
 * 手机侧请求 → 盒子返回 JSON（responseKey 为取版本号的键名）。
 * OTA 地址即各盒子固件页（耘电 http://192.168.0.1/）。
 */
object BoxOta {

    private const val TAG = "BoxOta"

    data class Result(val ok: Boolean, val version: String?, val raw: String?, val error: String? = null)

    /**
     * 向盒子 OTA 地址发检查请求。
     *
     * TODO-CALIBRATE: 盒子 OTA 的具体 HTTP 路径/method/参数未静态实证
     * （原版走 BoxCheckUpdateDialog 内的请求，字段见 BoxUpdateModel）。
     * 这里给最常用形态：POST {otaAddress} + paramjson，从响应 JSON 取 responseKey。
     */
    suspend fun check(req: BoxCheckUpdate): Result = withContext(Dispatchers.IO) {
        try {
            val url = URL(req.otaAddress.ifEmpty { return@withContext Result(false, null, null, "empty otaAddress") })
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = if (req.httpType == 2) "GET" else "POST"
                if (requestMethod == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (conn.requestMethod == "POST" && req.paramjson.isNotEmpty()) {
                conn.outputStream.use { it.write(req.paramjson.toByteArray()) }
            }
            val code = conn.responseCode
            val body = try {
                BufferedReader(InputStreamReader(
                    if (code in 200..299) conn.inputStream else conn.errorStream, Charsets.UTF_8
                )).use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }   // 防连接泄漏（原从不 disconnect）
            }
            Log.i(TAG, "ota http=$code body=$body")
            val version = runCatching {
                val json = JSONObject(body)
                // responseKey 可能是 "a.b.c" 形式
                var cur: Any? = json
                for (k in req.responseKey.split(".").filter { it.isNotEmpty() }) {
                    cur = (cur as? JSONObject)?.opt(k)
                }
                cur?.toString()
            }.getOrNull()
            Result(true, version, body)
        } catch (e: Exception) {
            Log.e(TAG, "ota error", e)
            Result(false, null, null, e.message)
        }
    }
}
