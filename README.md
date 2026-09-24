# Suwayomi-ext-runtime

扩展运行时：一份**共享源码树**（Mihon/Tachiyomi 扩展 API 实现 + 沙盒路由/驱动）+ **桌面 JVM 沙盒宿主**。
从 [`576576/Suwayomi-next`](https://github.com/576576/Suwayomi-next) 剥离而来，与 Rust 服务端只通过 **HTTP + JSON 契约**对话。

## 构建

```bash
./gradlew build   # 编译 + 测试
./gradlew jar     # fat jar → build/libs/ext-runtime.jar
```

需要 **JDK 25**。首次构建会下载 AOSP 公开 API 包（约 52 MB）生成 `android-stub`，换源用 `-PaospPackageUrl=<镜像>`。

裁剪 JRE（`+jre` 桌面包 / Docker 用）：

```bash
bash scripts/make-jre.sh windows x64 /tmp/jre   # <windows|linux|mac> <x64|aarch64> <输出目录>
```

## 版本号

`<AOSP API level>.<主版本>.<修订>`，例如 `30.1.0`。大版本跟着 `android-stub` 的 API 基线走；
`publish.yml` 强制 tag 大版本等于 pin 的 `aospApiLevel`，不一致直接失败。

## 制品与消费

打 `v<V>` tag 后发布到 **GitHub Release 资产**（免鉴权）与 **GitHub Packages**：

| 制品 | 用途 |
|---|---|
| `ext-runtime-<V>.jar` | 桌面 / 服务端 / Docker，部署为 `<发布根>/bin/ext-runtime.jar` |
| `ext-runtime-<V>-shared-sources.jar` | Android `extension-host` 展开后作为额外源根编译 |
| `ext-runtime-jre-<V>-<os>-<arch>.tar.gz` ×6 | `+jre` 桌面包 / Docker 镜像 |

消费方走 **Release 资产**。Packages 坐标（需 PAT `read:packages`）：

```
https://maven.pkg.github.com/576576/Suwayomi-ext-runtime
com.github.576576.suwayomi-ext-runtime:ext-runtime:<version>
```

Android 侧只能吃 sources：本仓库用 Kotlin 2.4.0，AGP 内置的 2.3.20 读不了 2.4 的元数据。

## 与 Suwayomi-next

运行时契约不变（路由、JSON 形状、主类 `sandbox.MainKt`、文件名 `bin/ext-runtime.jar`）。
本地调试设 `SUWAYOMI_SANDBOX_JAR` 指向 `build/libs/ext-runtime.jar` 即可，不用发版。
改了 `src/shared/` → 本仓库打 tag 发版 → 下游自动解析新版本；若新增 JVM 模块依赖，同步
`scripts/make-jre.sh` 的模块白名单。

## 许可

MPL-2.0（见 [`LICENSE`](LICENSE)）。第三方出处：AndroidCompat 桩（MPL-2.0）、
Mihon/Tachiyomi 扩展 API 形状（Apache-2.0, Copyright 2015 Javier Tomás）、AOSP 公开 API（Apache-2.0）。

## 文档

- [`docs/EXTRACTION_PLAN.md`](docs/EXTRACTION_PLAN.md) — 剥离施工计划
- [`docs/EXTRACTION_RECORD.md`](docs/EXTRACTION_RECORD.md) — 决策与试错记录（含目录布局、JRE 归属等细节）
