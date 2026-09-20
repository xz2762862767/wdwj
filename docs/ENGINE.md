# 内嵌沙箱引擎说明（app/libs/blackbox.jar）

本项目的「免 Root 沙箱启动 / 脱壳」能力来自仓库内嵌的一个 Android 沙箱引擎二进制。
因为它是第三方 GPL 系项目的衍生品，整个仓库才以 GPL-3.0 发布。本文记录它的身份、
来源与已知改动，供合规审阅与后续维护使用。

## 1. 身份（可在本仓库自行核验）

| 项 | 值 |
| --- | --- |
| 文件 | `app/libs/blackbox.jar` |
| 大小 | 682,692 字节 |
| class 数量 | 589 |
| md5 | `dfbcf50bcfcd5412927f44715d7c6e1d` |
| 主包名 | `top.niunaijun.blackbox` |

核验命令：

```bash
md5sum app/libs/blackbox.jar
unzip -l app/libs/blackbox.jar | grep -c '\.class'
```

## 2. 上游来源

- 上游项目：**BlackBox**（作者 niunaijun），一个以 **VirtualApp** 为基础的 Android 应用虚拟化 / 沙箱框架。
- VirtualApp 采用 GPL-3.0；BlackBox 作为其衍生项目，许可证**很可能同为 GPL-3.0**。
  本仓库按 GPL-3.0 处理，但**该结论尚未与上游 LICENSE 原文逐字核对**（整理本仓库时环境无联网检索能力）。
- 引擎内的脱壳能力来自 BlackBox 自带的 BlackDex 系实现。

> 待办：请在能联网时核对上游仓库的 LICENSE 文件，并在本文件补充确认结论与链接。

## 3. 已知改动（相对上游原始产物）

本仓库内的引擎**并非上游原始发布产物**，而是经过重打包（并可能做过类的增删）的版本：

- 同目录的参考版本（未随仓库分发，仅记录于开发环境）为 572 个 class / 592,710 字节，
  而在用的版本为 589 个 class / 682,692 字节，两者不一致，说明存在改动。
- 当时的类裁剪 / 补齐输入清单保留在 `docs/engine/`：
  - `blackbox_components.xml`、`components_final.xml`、`components_include.txt`：组件（类）清单
  - `fix_components.py`：按清单补齐 / 修正组件的脚本

> 待办（GPL-3.0 §6 合规）：分发二进制时需一并提供「对应的源码」。当前仓库**不包含引擎源码树**，
> 后续需要二者之一：
> 1. 补发一个与 `blackbox.jar` 对应的引擎源码（或补发布一个 downlink + 修改说明）；
> 2. 或改为直接引用上游原版引擎产物（其对应源码即上游仓库），并接受可能的功能差异。

## 3.1 原生库

`app/src/main/jniLibs/arm64-v8a/` 下的 6 个 .so 属于脱壳路径上的原生组件
（libblackdex.so、libblackdex_d.so、libcdumpdex.so、libdump.so、libgodump.so、libxp_godump.so）。
其中 libblackdex*.so 来自 BlackDex 一脉，其余几个（dump / godump / xp_godump）来自其它第三方脱壳项目。
**这些库的上游与许可证尚未核实**，公开发布前需要补做，做法见 NOTICE 中的同一项待办。

## 4. 如何替换 / 重建引擎

`app/build.gradle` 通过 `fileTree(dir: 'libs', include: ['*.jar'])` 引入 `app/libs/` 下的所有 jar，
因此替换引擎只需：

1. 取得（或自行编译）目标引擎 jar；
2. 覆盖为 `app/libs/blackbox.jar`；
3. 重新执行 `gradle assembleDebug`。

注意：引擎的类名 / 资源引用可能被本项目源码依赖（例如 `top/niunaijun/blackbox/R.java`），
替换大版本引擎后需要重新验证沙箱启动与脱壳路径。
