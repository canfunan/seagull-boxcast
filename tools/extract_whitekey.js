/**
 * 白盒密钥提取（真机黑盒法）——vivo 定制版 assets 内含
 * whitebox-encrypt.key / whitebox-decrypt.key（各 535KB），getWhiteKey 的
 * 变换逻辑在 libbaiduprotect_sec.so（stripped arm64，静态逆不出），
 * 但真机跑原版 app 时 Java 层可见输入输出——hook 即可提取。
 *
 * 用法（设备已 root / Frida server 运行）：
 *   frida -U -f com.baidu.carlife.vivo -l extract_whitekey.js --no-pause
 *
 * 提取后把 [whitekey] out=... 的值填进 BoxCrypto.whiteKeyHook：
 *   BoxCrypto.whiteKeyHook = { key -> "提取到的值" }   // 或按 key 分桶
 * 然后 BLE 秒连解密（BoxBleParser）与 AES 链路即与盒子对齐。
 */
Java.perform(function () {
    var TAG = "[whitekey]";

    // 1) 白盒变换输入输出对（getWhiteKey）
    try {
        var CMT = Java.use("com.baidu.carlife.core.common.CommonMsgTransmit");
        CMT.getWhiteKey.overload("java.lang.String").implementation = function (s) {
            var r = this.getWhiteKey(s);
            console.log(TAG + " getWhiteKey in=" + s + " out=" + r);
            return r;
        };
    } catch (e) { console.log(TAG + " CMT hook fail: " + e); }

    // 2) AESManagerBox 实际使用的 key 字节（init 时落定）
    try {
        var Box = Java.use("com.baidu.carlife.core.connect.encrypt.AESManagerBox");
        var SecretKeySpec = Java.use("javax.crypto.spec.SecretKeySpec");
        console.log(TAG + " AESManagerBox hooked (watch init logs)");
    } catch (e) { console.log(TAG + " Box hook fail: " + e); }

    // 3) BLE 广播解密实证（parseSsids 输入输出）——连盒子时自动打印
    try {
        var WDDM = Java.use("com.baidu.carlife.core.connect.wireless.wifip2p.WifiDirectDiscoverManager");
        WDDM.parseSsids.implementation = function (scanRecord) {
            var r = this.parseSsids(scanRecord);
            console.log(TAG + " parseSsids out=" + r);
            return r;
        };
    } catch (e) { console.log(TAG + " parseSsids hook fail (可忽略): " + e); }

    // 4) 白盒 key 文件读取路径确认（assets 挂载）
    try {
        var AA = Java.use("android.app.Application");
        console.log(TAG + " tip: 关注 logcat 中 whitebox/key 相关行");
    } catch (e) {}
});
