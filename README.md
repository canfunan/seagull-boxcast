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
