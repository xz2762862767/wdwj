# 直接下载（APK）

| 文件 | 版本 | 大小 | md5 |
| --- | --- | --- | --- |
| `wdwj-v5.9.56-debug.apk` | 5.9.56（versionCode 107） | 8,109,749 字节 | `a70355e802c6473e6a302308c781af73` |

## 这是什么

由**本仓库源码**构建的产物（`gradle assembleDebug`），versionName / versionCode 与源码一致。

## 安装前必读

- 这是 **debug 构建**，由构建机上的 debug 密钥库签名，不是发布签名。
- 因此**不同机器重新构建出的 APK 签名互不相同**：想覆盖安装官方版本、或安装别人的构建版，都需要先卸载旧版本。
- 要求 Android 8.0（API 26）及以上。
- 包名：`com.mtstyle.fm`

## 想要自己发布

按 README 的构建步骤自行构建，并用你自己的密钥签名（应用内「写回 APK」用的自签密钥与安装包签名是两码事）。

## 许可

本 APK 与仓库同为 GPL-3.0 发布，其中内嵌 BlackBox 沙箱引擎等第三方组件，
完整清单见仓库根目录 `NOTICE`，引擎来源与改动见 `docs/ENGINE.md`。
