# Seagull BoxCast — 海鸥车机桌面 × 百度互联盒子投屏（整合版）

> 由两个独立项目整合而成，**各自原仓库不受影响**：
> - BoxCast —— 百度互联无线盒子（耘电/junyong/liaoyuan/suding）投屏 + 反控客户端（协议逆向实证）
> - [canfunan/seagull-desktop](https://github.com/canfunan/seagull-desktop) —— 海鸥车机桌面（多窗口 HMI，205 条功能）

包名 `com.seagull.boxcast`，单 APK：**既是投屏器，也是车机桌面**。

## 整合架构

```
┌──────────────────────── BoxCast APK (com.seagull.boxcast) ────────────────────────┐
│                                                                                  │
│  ┌──────────────────┐   ┌─────────────────────┐   ┌──────────────────────────┐  │
│  │  BoxCast 投屏层   │   │   海鸥桌面 HMI       │   │  画中画板 PipBoard        │  │
│  │  · 盒子发现/连接  │──▶│  · HomeActivity      │◀──│  · MirrorSlot(VD 第三方)  │  │
│  │  · Hello/授权     │   │  · DesktopView/Dock  │   │  · BoxStreamSource(远端流)│  │
│  │  · H264 编码发送  │   │  · 天气/歌词/任务    │   │                          │  │
│  │  · 四通道反控     │   │                      │   │                          │  │
│  └──────────────────┘   └─────────────────────┘   └──────────────────────────┘  │
│           │                        ▲                                            │
│           │ MediaProjection 截前台  │ Window.injectInputEvent 直达（零权限）      │
│           ▼                        │                                            │
│      编码 → WiFi/USB ──▶ 盒子      │ 车机触摸                                      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## 核心能力

| 能力 | 实现 | 权限 |
|---|---|---|
| 投屏到盒子 | MediaProjection → MediaCodec H.264（832x480~1080p/码率帧率可调）→ 盒子协议 | 无特殊 |
| 盒子连接 | Hello 握手 + tag 认证（`type_mac_active_randomvalue`）+ 授权五态 + 心跳 | 无特殊 |
| 内置 HMI | 海鸥桌面整入（103 源文件），双 LAUNCHER：投屏器 + 默认桌面 | 无特殊 |
| **反控（零权限）** | **`Window.setLocalFocus` + `Window.injectInputEvent`**（CarLife+ v8.9.5 实证同款），直达前台海鸥 Activity | **无任何条件** |
| 反控（全系统） | root `input` 命令 / 无障碍 `dispatchGesture` / 系统签名反射，三通道兜底 | root/手动/签名 |
| 画中画挂第三方 | 海鸥 PipBoard：MirrorSlot（VD 跑高德等，root）+ BoxStreamSource（远端流解码渲染） | VD 需 root |

## 构建

Gradle（AGP 8.5.2 / Kotlin 1.9.24 / JDK 17）：
```bash
./gradlew :app:assembleDebug
```
CI：推 main 自动构建。

海鸥源码位于 `seagull/src`（Java）+ `seagull/res`，经 `sourceSets` 挂载；R 引用已适配为 `com.seagull.boxcast.R`；manifest 组件已合并（海鸥类名保留 `com.seagull.carlauncher.*`）。

## 逆向实证来源（BoxCast 协议层）

| 样本 | 包名 | 贡献 |
|---|---|---|
| 2021 CarLife 真身 | net.icarbox.ailink | 双层帧格式、channel 语义、AOA/WiFi 双栈、触摸注入 |
| 2026 车机端 | com.baidu.carlifevehicle | CarLife 4 代协议消息字典、四传输层 |
| vivo 定制版 | com.baidu.carlife.vivo | **盒子协议全部实证**（耘电等盒子 Hello/认证/H264/OTA） |
| CarLife+ v8.9.5 | com.baidu.carlife | **`Window.injectInputEvent` 零权限反控实证** |

## 第九轮深挖实证：标准视频推流路径 + 前后台空包 + 双向暂停（Recorder）

**标准视频流**（`Recorder.sendVideoData` + `ConnectManager.writeVideoData` 实证）：
- `MSG_VIDEO_DATA=131073` 走 **channel 2**（VideoSocket 8240 / AOA channel 2）
- **两段写**：`[12B 头][H.264]`——头与音频同款：`[dataLen:u32 BE][timeStamp:u32 BE(秒)][00 02 00 01]`
- H.264 可经 AESManager 加密（`EncryptSetupManager.isEncryptProcessEnable`）

**前后台空包**（`sendEmptyPacketForeground/Background` 实证）：dataLen=0，type=01 前台 / 02 后台——退后台发空包告知车机（非消息通道）

**双向暂停仲裁**（Recorder 实证）：`pauseFromHeadUnit`/`resumeFromHeadUnit`（车机 MSG_CMD_VIDEO_ENCODER_PAUSE/START）
+ `pauseFromMobile`/`resumeFromMobile`（手机端）双标志，任一停即停推

**画中画可投性结论**：官方 Recorder 也是 MediaProjection 截 display 0（与本工程一致）；
海鸥 PipBoard 的 MirrorSlot/BoxStreamSource 画面经 SurfaceView 呈现在 display 0，
MediaProjection 镜像 display 0 理论上可截到——**SurfaceView+MediaProjection 在部分 ROM
有黑屏/不同步兼容坑，需真机验证；不稳则把槽位 SurfaceView 改 TextureView**。

**本工程落地**：`video/VideoSender.kt`（标准路径：12B 头 + channel 2 + 前后台空包 + 双向暂停）；
ScreenCastService 双视频路径开关（点播 102409 裸帧 / 标准 131073+头，extras `use_standard_video`，默认标准）；
CmdId 补 MSG_VIDEO_DATA/MSG_VIDEO_HEARTBEAT。

## 第八轮深挖实证：音频通道全链路（PCMMediaManager/MediaChannelSend/PCMPackageHead）

**通道分配**（端口表实证）：
- **channel 3 = 媒体音频**（MediaSocket 9240）——音乐/媒体
- **channel 4 = TTS**（TTSSocket 9241）——导航语音
- **channel 5 = VR**（VRSocket 9242）——语音识别
- 控制命令（MSG_CMD_MIC_RECORD_*=65570/65571/65572）走 channel 1

**PCM 裸流帧**（PCMPackageHead 实证）：`[dataLen:u32 BE][timeStamp:u32 BE][type:u32 BE][payload]`（12B 头）

**消息族**（CommonParams 实证）：
- 媒体 196xxx：INIT=196609 / STOP=196610 / PAUSE=196611 / RESUME=196612 / SEEK=196613 / **DATA=196614(PCM)** / **DATA_ENCODE=196615(AAC)**
- TTS 262xxx：INTERRUPT=262149 / FEED_END=262150 / DONE=294919
- VR 327xxx：INIT=327682 / DATA=327683 / STOP=327684 / FEED_END=327688 / DONE=360457
- 包类型枚举 EMusicPackageType：INIT/STOP/PAUSE/RESUME/SEEK/NORMAL ↔ 上述消息

**初始化握手**（sendInitParameter 实证）：
- `CarlifeMusicInit{sampleRate, channelConfig, sampleFormat}`（proto 字段 1/2/3）
- 默认 **44100Hz / 2 声道 / 16bit**（越界强制回退：<4000 或 >48000 → 44100；声道非 1/2 → 2；格式非 8/16 → 16）
- INIT 包 = [12B PCM 头(type=196609)][MusicInit proto]；proto 可经 AESManager 加密（EncryptSetupManager 开关）

**本工程落地**：`audio/AudioChannel.kt`（PcmHead 12B 构建 + MusicInit 编解码 + Sender 状态机 INIT→DATA→PAUSE/RESUME/SEEK/STOP）；
ScreenCastService 投屏时同步采音频（AudioRecord 44100/2/16 → channel 3）；CmdId 补全音频消息族；sendRaw 支持指定 channel。
TODO-CALIBRATE：投屏内录（REMOTE_SUBMIX 音源）需系统特权，普通应用只能采 MIC——车内场景建议后续换音源或走 AAC 模式。

## 第七轮深挖实证：车型渠道表 + AMIS 机制全量出土（63 项）

**车型渠道表**（`CommonParams.VehicleChannel` enum 全量，格式 `2\d{7}` 8 位数字）：
- 来源：CarLife 握手时车机自报（`getVechileChannel`：connected && hasRecvChannel → 协议值；断连用 SP 缓存）
- 编码规律：前 4 位品牌段（2002 现代/2003 起亚/2004 奥迪/2005 上汽通用/2006 戴姆勒/2007 松下/2008 大众/2009 长安/2010 哈曼/2011 德尔菲/2012 华阳/2013 阿尔派/2014 先锋/2015 路畅/2016 飞歌/2017 伟世通/2018-2021 上汽通三品牌+比亚迪/2022 远峰+雪佛兰K216/2028 一汽丰田/2030 开悦/2031 吉利几何/2045 广汽丰田/2048 远峰ELH/2054 远峰伊利/2107 RAV4），后 4 位 x100=品牌默认 x1xx=车型 x2xx=双音频
- 市场标记：20000000 后装 / 20000001 前装预装 / 20011100 普通
- 已落地 `VehicleChannels.kt`（63 常量 + 中文名 + isValid）

**AMIS 优化机制**（`AmisConfigManager`+`VideoResolution` 实证）：
- JSON：`[{"cn":"20181100","rate":码率,"frame":帧率,"highrate":bool}]`，按 cn 匹配同时取码率+帧率
- 特例：rate<=0 且 highrate=true → rate=1（高速通道标记）
- 已落地 `VehicleProfile.kt`（内置实证默认 + 同格式 JSON 运行时覆盖）——**自建车型参数库即可复刻官方云端下发**

**顺带挖出**：
- `assets/car_brand.json`（1.28MB）——百度地图车型品牌库（选车 UI 数据源，version 哈希+头像 URL）
- `assets/supplierconfig.json`——`{"supplier":{"vivo":{"appid":"100215079"}}}` vivo 定制版百度 appid
- `assets/config.json`——**硬编码百度账号凭据**（bduss/clientId/accessToken/uuid，泄露）

## 第六轮深挖实证：官方参数优化体系（AmisConfigManager / VideoResolution / VideoMsgHandler）

**码率/帧率——按车机型号云端下发**（`VideoResolution.getSpecialBitRate` 实证）：
- AMIS 配置 JSON：`[{"cn":"车型渠道","rate":码率,"frame":帧率,"highrate":bool}]`
- 运行时按 `getVechileChannel()`（车机型号标识）匹配 → 同时返回专属**码率+帧率**
- `highrate=true` 且 rate<=0 时置 1（高速通道标记）

**分辨率——三级决策**（`getResolution` 实证）：
- 用户自定义（TYPE=2）> 白名单推荐（TYPE=1，`KEY_RESOLUTION_WHITELIST`）> 默认（TYPE=0）
- `KEY_MORE_RESOLUTION` 云端下发更多档位；`SpecialResolution` 特殊编码分辨率（异性屏）
- 机型门槛：`KEY_CORENUM_DEFINITION`（CPU 核心数）、`KEY_SDK_DEFINITION`（SDK 高清支持）

**帧率——运行时闭环协商**（`VideoMsgHandler` 实证）：
- HU 下发 `MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE(98316)` → `Recorder.changeFrameRate(fps)`
  → 回 `MSG_CMD_VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE(98317)`——卡顿/负载高时车机主动降帧

**音频优化**：
- `KEY_PCM_RESAMPLE_BLACKLIST` / `KEY_FIXED_PCM_SAMPLERATE_LIST` / `KEY_RESAMPLE_SWITCH`——PCM 重采样黑白名单（车机音频通道采样率适配）
- `AAC_SUPPORT` / `MEDIA_SAMPLE_RATE` / `AUDIO_TRANSMISSION_MODE` / `KEY_OPTIMIZE_RECORD`——编码格式与传输模式
- record/media/tts 三通道独立采样率/格式/声道配置

**屏幕适配**：`KEY_BLACK_SCREEN_SCALE`（黑边名单）、`KEY_REAL_SCREEN_SCALE`、`KEY_HETEROMORPHIC_SCREEN`（刘海/圆角）、`setCalibration`（触摸校准）、`KEY_POINTER_SCALING_FORBIDDEN`

**芯片特判**：SM6375 降 1920x1140（BoxH264Manager）、mt3561→720 宽（2021 h/g.java）

**本工程落地**：`H264Encoder.changeFrameRate()`（98316 协商 → reconfigure → 可接 98317 DONE）；
UI 参数面板已支持 640x360~1080p / 1-20Mbps / 5-60fps 静态调节。

## 第五轮深挖实证：盒子发现与连接全链路（vivo 版 BleBluetoothDiscover / WifiDirectDiscoverManager）

**发现（BLE）**：
- `ScanFilter.setServiceUuid("c62857aa-6ca1-4ac4-8842-3ca71aef6765")`——盒子 BLE 服务 UUID
- 广播名匹配关键词 `"box"`（`TARGET_NAME`，小写包含）
- 华为 ROM 豁免 UUID 过滤（华为 BLE 的 UUID 过滤有 bug 会不停上报）；`SCAN_MODE_LOW_LATENCY`
- Timer 定时扫描，最多 20 轮，已连 CarLife 即停；需定位权限
- 命中后 `saveBoxMac2Local()` 存 ble_mac/ble_name/is_box（配对记忆）
- `MixConfig.getConnectType() == 13` 才启用（13 = BLE 盒子发现模式）

**连接（BLE → WiFi P2P 秒连）**：
- `ble2p2p(scanResult)` → `WifiDirectDiscoverManager.connect(name, scanRecord.bytes, null)`
- `parseSsids(scanRecord)`：BLE Manufacturer Data（AD type 0xFF）有**两段**——第一段跳过（厂商魔数），
  第二段 = `[16 字节密文][剩余明文]`：
  `plain = AES_ECB_decrypt(data[0:16]) XOR aesKey()`（密钥即 `H22/emBpCuspF1DYYaJjxQ==`）→
  `(plain + tail).split(",")` = **[ssid, password, frequency?]**
- `connectOnThread([name, ssid, password, freq])`：
  - **SDK ≥ 29**：`connectP2pDeviceBySsid(ssid, password, freq)`——SSID+密码 P2P 直连，免对端发现
  - **SDK < 29**：经典 P2P discoverPeers → `connectP2pDevice(device)`（invite）
- P2P 建立后 `onConnectionInfoAvailable(WifiP2pInfo)` 拿 groupOwnerAddress/isGroupOwner
- 30 秒连接超时（`TimeoutKt.withTimeout(30000)`）；`onConnectChange(5, ...)` = 连接中

**本工程落地**：`BoxBleParser.kt` 完整复刻 parseSsids（UUID 过滤+双 0xFF 段+AES 解密+XOR+split），
`BoxDiscovery` BLE 扫描已升级为原版同款（UUID/华为豁免/LOW_LATENCY/名过滤/凭据解析），
UI 卡片直接展示解出的 ssid/pwd/freq——**BLE 一扫即得热点凭据，无需用户输 WiFi 密码**。

**仍未实证**：P2P 建连后的应用层 socket 端口（静态无硬编码）——nmap 实测。

## 第四轮深挖实证（2026-10 复盘）

**帧头双格式实锤**（`CarlifeCmdMessage.java` toByteArray/fromByteArray）：
- channel ∈ {1,6}（Cmd/Touch）→ **8 字节头** `[length:u16 BE][reserved:u16 BE][serviceType:u32 BE]`
- channel ∈ {2,3,4,5}（Video/Media/TTS/VR）→ **12 字节头** `[length:u32 BE][?:u32 BE][serviceType:u32 BE]`
- 盒子点播协议（1024xx）全部 `new CarlifeCmdMessage(true)` → **mChannel=1，8 字节头**——本工程 `inner()` 实现与实证一致
- `Wire.kt` protobuf 编解码已通过 round-trip 交叉验证（含 int32/int64 边界值）

**消息路由实锤**（`CarlifeConnectHsgHandler:772-780`）：
- 102403 Hello → `setBoxAuthorized(3)` 预设 → `BleBluetoothDiscover.saveBleMac2Local()`（**BLE 发现盒子存 MAC 是连接前置**）→ `recvBoxHello`（tag 细化）→ `BoxH264Manager.init` + 统计
- **授权码不禁用流程**：原版 tag 不符也继续 init H.264——本工程 UI 门限已对齐（Hello 收到即可投屏，授权码仅展示）
- 102401 CHANGE_FRAME_WH：全代码无使用方（预留常量），发送无接收方
- TCP 角色/端口：静态无实证（连接建立在 ConnectClient 层，未挖到硬编码端口）——留 nmap 实测

## 待校准（真盒子实测）

1. 盒子数据端口（候选 7240/8240/9340/9800/18899/40001）
2. VerifyCode 算法（native so 直调 or MD5/SHA 候选，抓一次 Hello 即可锁定）
3. H264Send 字段序、分片大小
4. 盒子→手机视频流 serviceType（实测后接入 `BoxStreamSource.feedFrame`）
5. 耘电盒子是否下发 425985/425992 反控段

详见代码内 `TODO-CALIBRATE` 标注。

## 许可

- BoxCast 部分：学习与自有设备使用
- 海鸥桌面部分：遵循其原仓库许可（reference/ 材料版权归各自作者）
