# 我的文件（MT 风格文件管理器）

一个 Android 文件管理器，内置 APK / DEX 逆向工具链：可以直接在手机上查看反编译代码、编辑 Smali 并写回 APK，全程不需要电脑端工具。

## 功能

- 双面板文件浏览（默认左侧根目录 `/`、右侧内部存储，可把任意目录设为面板主页）
- 复制 / 移动 / 删除 / 重命名 / 新建，带目标目录可写性预检与中文提示
- APK 详情页：图标、应用名、包名、版本、签名状态（含 SHA-256）、加固检测、数据目录
- DEX 查看：类 / 方法 / 字段列表，Java（集成 jadx）与 Smali 一键切换
- **代码查看与编辑都支持双指缩放字号**，缩放比例会被记住
- **Smali 编辑并写回 APK**：编辑单个方法 → 重新汇编 → 就地补丁 DEX → 重新打包 → 用本机密钥重新签名（APK Signature Scheme v2）
- APK 安装：Shizuku / Root / Dhizuku / 自定义安装器，可配置是否校验签名

## 技术实现

| 模块 | 说明 |
| --- | --- |
| `apk/Dex.java` | 自研 DEX 解析器（不依赖 baksmali） |
| `apk/SmaliAssembler.java` | 自研 Smali 汇编器，往返一致性在 10MB / 52617 个方法上 100% 字节相同 |
| `apk/DexMethodPatcher.java` | 方法级就地补丁，自动刷新校验和与 SHA-1 签名 |
| `apk/ZipEdit.java` | APK 条目替换与重打包 |
| `apk/ApkSignerV2.java` | 自研 APK Signature Scheme v2 签名器（不依赖 apksigner） |
| `apk/SigningKey.java` | 首次使用时在本机生成 RSA 2048 密钥与自签 X.509 证书 |
| `PinchZoom.java` | 查看 / 编辑共用的双指缩放 |

## 构建

```bash
gradle assembleDebug --no-daemon -Dorg.gradle.vfs.watch=false
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

要求 JDK 17 与 Android SDK 34（compileSdk 34 / minSdk 26）。第三方依赖（jadx-core、Guava、Gson、slf4j）已随仓库放在 `app/libs/`，离线也能构建。

## 签名密钥

仓库与 APK 中**不包含任何私钥**。首次使用「写回 APK」时，应用会在设备上生成一对 RSA 2048 密钥和自签证书，保存在应用私有目录 `files/signkey/`。

因此每个用户签出的 APK 签名都不同：覆盖安装官方版本前需要先卸载。

## 已知限制

- 只能编辑「单个方法」，且新代码不能长于原方法的代码槽（超出会提示精简指令）
- `switch` / `array-data` 数据区只支持等长编辑
- 字符串常量池不可扩容，无法新增字符串常量
- 暂不支持整类编辑（改字段 / 注解 / 新增方法）
- 输出文件为 `<原名>_edit.apk`，签名与原 APK 不同

## 第三方组件

随仓库分发的第三方二进制位于 `app/libs/`：

| 组件 | 许可证 |
| --- | --- |
| jadx（jadx-core、jadx-dex-input、jadx-input-api） | Apache-2.0 |
| Guava、failureaccess、error_prone_annotations | Apache-2.0 |
| Gson | Apache-2.0 |
| slf4j-api、slf4j-nop | MIT |
| apksig（Android SDK Build Tools） | Apache-2.0 |
| dexlib2（smali 项目） | 见上游声明 |
| **BlackBox 沙箱引擎**（含 BlackDex 系脱壳实现） | **GPL 系（VirtualApp 衍生物）** |

逐项的完整说明见 [NOTICE](NOTICE)；引擎的来源、md5 与已知改动见 [docs/ENGINE.md](docs/ENGINE.md)。

## 许可证

**GNU General Public License v3.0**，全文见 [LICENSE](LICENSE)。

本项目整体采用 GPL-3.0，原因是仓库内嵌了一个 GPL 系的 Android 沙箱引擎二进制
（`app/libs/blackbox.jar`，BlackBox / VirtualApp 衍生物）。实际含义：

- 你可以自由使用、修改、再分发本项目；但**再分发（含修改版）必须同样以 GPL-3.0 开源**，
  并向接收者提供完整的对应源码；
- 未内嵌引擎的自有源码部分同样以 GPL-3.0 授权。

## 免责声明

本项目仅供学习与研究使用。请勿用于破解、篡改他人应用或违反当地法律法规的用途。
