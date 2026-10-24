# 真机提取工具

## extract_whitekey.js——白盒密钥提取

vivo 定制版（com.baidu.carlife.vivo）assets 内：
- `whitebox-encrypt.key` / `whitebox-decrypt.key`（各 535KB）
- `pass-key.face-android`（533KB，人脸活体）
- `carlifevivo_20230221.pkg.so`（vivo 定制加固包）

`CommonMsgTransmit.getWhiteKey(input)` 的真实变换在 `libbaiduprotect_sec.so`
（stripped arm64 native），静态逆不出——**真机黑盒提取**：

```bash
# 设备 root + frida-server
frida -U -f com.baidu.carlife.vivo -l extract_whitekey.js --no-pause
# 触发一次盒子连接/蓝牙扫描，观察输出：
#   [whitekey] getWhiteKey in=H22/emBpCuspF1DYYaJjxQ== out=XXXX
#   [whitekey] parseSsids out=[ssid, password, freq]
```

把 `out=` 的值配到 `BoxCrypto.whiteKeyHook`（按 in 分桶），
`BoxBleParser` 的 BLE 秒连解密即与盒子对齐。

## 其他真机校准点

| 项 | 方法 |
|---|---|
| 盒子数据端口 | 手机连盒子热点后 `nmap -p- 192.168.0.1` |
| 两条视频路径 | 投屏日志对照（默认标准路径 131073，黑屏切 `use_standard_video=false`） |
| 加密是否开启 | Hello 后是否收到 98411（HU_RSA_RESPONSE） |
| VerifyCode 算法 | Hello 日志里 `matchKnown(input, tag)` 验证 md5-hex-salted |
