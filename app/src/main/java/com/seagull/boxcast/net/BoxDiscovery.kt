package com.seagull.boxcast.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 盒子发现（三路并联，对应逆向出的 wireless 包）：
 *
 * 1) BLE 广播名（BluetoothScanCallBack / boxBleDialog）：盒子 BLE 名含 carlife/盒子类型关键词
 * 2) UDP 辅助发现（WifiP2pUdpHelpModel 的 UDP 通道）：局域网广播探测
 * 3) 已知网段直探（OTA 地址反推）：耘电 192.168.0.1 / 辽源·速鼎 192.168.1.101 / 军用 192.168.50.2
 *
 * TODO-CALIBRATE: 原版 UDP 辅助发现的魔数/端口未从静态代码挖出（在 native/混淆层），
 * 这里用"端口可达性探测"替代：连上盒子 WiFi 后直接探数据端口，通了就是盒子。
 */
class BoxDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "BoxDiscovery"
        private const val UDP_PORT = 9999           // TODO-CALIBRATE: 辅助发现端口（未实证）
        private val NAME_KEYWORDS = listOf("carlife", "yundian", "耘电", "junyong", "liaoyuan", "suding", "box")
    }

    data class Found(
        val name: String,
        val host: String,
        val via: String,               // ble / udp / probe
        val dataPort: Int? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _boxes = MutableStateFlow<List<Found>>(emptyList())
    val boxes: StateFlow<List<Found>> = _boxes

    private val found = LinkedHashMap<String, Found>()

    private fun add(f: Found) {
        val key = "${f.name}@${f.host}"
        if (!found.containsKey(key)) {
            found[key] = f
            _boxes.value = found.values.toList()
            Log.i(TAG, "found box: $f")
        }
    }

    // ---------- 1) BLE ----------

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val name = result?.scanRecord?.deviceName ?: result?.device?.name ?: return
                if (NAME_KEYWORDS.any { name.contains(it, ignoreCase = true) }) {
                    add(Found(name, host = "", via = "ble"))
                }
            }
        }
        adapter.bluetoothLeScanner?.startScan(cb)
    }

    // ---------- 2) UDP 广播 ----------

    fun startUdpListen() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    DatagramSocket(null).use { sock ->
                        sock.reuseAddress = true
                        sock.broadcast = true
                        sock.bind(InetSocketAddress(UDP_PORT))
                        val buf = ByteArray(512)
                        while (true) {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            val text = String(p.data, 0, p.length)
                            if (NAME_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
                                add(Found(text.take(32), p.address.hostAddress ?: "", "udp"))
                            }
                        }
                    }
                }.onFailure { Log.w(TAG, "udp listen: ${it.message}") }
            }
        }
    }

    fun sendUdpProbe() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    DatagramSocket().use { sock ->
                        sock.broadcast = true
                        val data = "carlifehost_probe".toByteArray()
                        sock.send(DatagramPacket(data, data.size,
                            InetAddress.getByName("255.255.255.255"), UDP_PORT))
                    }
                }
            }
        }
    }

    // ---------- 3) 已知网段直探（最实用） ----------

    /**
     * 当前手机连了盒子热点后，直接探盒子数据端口。
     * 覆盖 OTA 表里的三个网段 + 本机网段。
     */
    fun probeKnownNetworks(onFound: (Found) -> Unit = { add(it) }) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val targets = linkedMapOf(
                    "192.168.0.1" to BoxTypes.TYPE_YUNDIAN,
                    "192.168.1.101" to BoxTypes.TYPE_LIAOYUAN,
                    "192.168.50.2" to BoxTypes.TYPE_JUNYONG,
                )
                // 加上本机所在网段的常见盒子地址
                localGateway()?.let { targets[it] = BoxTypes.TYPE_UNKNOW }

                for ((ip, type) in targets) {
                    for (port in BoxConnection.DATA_PORT_CANDIDATES) {
                        if (reachable(ip, port)) {
                            onFound(Found("box-$type", ip, "probe", port))
                            break
                        }
                    }
                }
            }
        }
    }

    private fun localGateway(): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.dhcpInfo?.gateway ?: return null
        // DhcpInfo.gateway 是网络序整数：最高位在最高字节 → 大端展开
        InetAddress.getByAddress(
            byteArrayOf(
                ((ip ushr 24) and 0xFF).toByte(), ((ip ushr 16) and 0xFF).toByte(),
                ((ip ushr 8) and 0xFF).toByte(), (ip and 0xFF).toByte()
            )
        ).hostAddress
    }.getOrNull()

    private fun reachable(ip: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), 600) }
        true
    }.getOrDefault(false)

    private fun hasPerm(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun clear() {
        found.clear()
        _boxes.value = emptyList()
    }
}
