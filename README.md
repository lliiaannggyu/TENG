# TENG 文件管理器（Wear OS）

圆形表盘上的文件管理器：浏览、筛选、排序、多选、复制/剪切/粘贴、重命名、删除（进回收站）、
压缩/解压（ZIP / 7z）、分享、局域网互传、常见格式查看器、安装包校验与安装。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

> 完整设计（几何规范、交互契约、性能预算、权限合规、协议时序、待确认项决议）见
> [`docs/架构与交互设计.md`](docs/架构与交互设计.md)。

## 环境

| 项 | 版本 |
|---|---|
| compileSdk / targetSdk | 36 |
| minSdk | 30 |
| Kotlin | 2.2.10（Compose Compiler Plugin） |
| AGP | 9.2.1 |
| Wear Compose | material3 / foundation 1.5.6 |

## 依赖选型

| 能力 | 库 | 为什么不用别的 |
|---|---|---|
| 列表 | `androidx.wear.compose.foundation.lazy.ScalingLazyColumn` | 自带"越靠上下越小"的弧形缩放 + 表冠旋转，自己写会丢掉这两点 |
| **UI 组件（M3E）** | `androidx.wear.compose.material3` | 官方 Material 3 Expressive for Wear：`AppScaffold` / `ScreenScaffold` / `TimeText`（时钟）/ `Card` / `AlertDialog` / `Dialog` / `SwitchButton` / `RadioButton` / `TextToggleButton` / `CircularProgressIndicator` / `LinearProgressIndicator` / `ScrollIndicator` / `ListHeader`，映射表见文档 §12 |
| 缩略图 | Coil（`coil-compose` + `coil-video`） | 视频首帧解码不用自己接 MediaMetadataRetriever |
| 音视频 | Media3 `ExoPlayer` + `media3-ui` | — |
| PDF | 系统 `PdfRenderer` | 零依赖，手表上不需要复杂排版 |
| ZIP | zip4j | AES-256 加密、分卷、逐 entry 流式解压 |
| 7z | Apache commons-compress | 唯一的成熟 JVM 7z 实现 |
| 偏好 | DataStore Preferences | 串行写盘，避免 SharedPreferences 的 apply 抖动 |
| 存储 | `DocumentsContract`（SAF） | 不用 `DocumentFile.listFiles()`（N+1 次跨进程查询） |

## 目录结构

```
app/src/main/java/com/tengwear/tengfilemanager/
├─ App.kt                     Application（Coil 视频解码器 / 回收站清理）
├─ core/                      model · fs · archive · apk · lan · prefs · util
├─ data/                      FileRepository（缓存 / 排序过滤 / 搜索）
└─ presentation/              MainActivity · ViewModel · theme · ui
docs/架构与交互设计.md
```

## 权限

- **不申请** `READ/WRITE_EXTERNAL_STORAGE`、`MANAGE_EXTERNAL_STORAGE`、`READ_MEDIA_*`。
- 默认只能访问：应用沙盒 + 用户通过系统文件选择器授权的目录。
- 申请 `REQUEST_INSTALL_PACKAGES`：仅用于把用户选中的 APK 交给**系统安装器**，不做静默安装。
- 局域网：`INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`
  + `FOREGROUND_SERVICE(_DATA_SYNC)`（息屏传输保活）。

## 已知限制

1. 7z **加密创建**、7z 分卷合并解压未支持（有明确 UI 提示）。
2. 局域网传输为明文直连（同 Wi-Fi），配对靠 4 位码人工比对；TLS/ECDH 见文档「后续演进」。
3. 文件夹不直传，需先压缩。

## 开源协议

本项目采用 [GNU AGPL v3](LICENSE) 开源协议。

AGPL v3 是基于 GPL v3 的强传染性开源协议，额外增加了第 13 条：**如果你修改了本程序并通过网络向用户提供服务，必须向所有用户公开你的修改版完整源代码。**

简单说：

- 你可以自由使用、修改、分发本项目
- 修改后的代码必须同样以 AGPL v3 开源
- 即使你只是通过网络提供服务（不分发二进制），也必须开源你的修改

## 构建与安装

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建（需要配置签名密钥）
# 在 local.properties 中添加：
# release.storeFile=release-key.jks
# release.storePassword=******
# release.keyAlias=tengwear
# release.keyPassword=******
./gradlew :app:assembleRelease
```

构建产物位于 `app/build/outputs/apk/`。

安装到 Wear OS 设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
