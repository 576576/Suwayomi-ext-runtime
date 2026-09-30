# Suwayomi-ext-runtime

扩展运行时：一份**共享源码树**（Mihon/Tachiyomi 扩展 API 实现 + 沙盒路由/驱动）+ **桌面 JVM 沙盒宿主**。
从 [`576576/Suwayomi-next`](https://github.com/576576/Suwayomi-next) 剥离而来，与 Rust 服务端只通过 **HTTP + JSON 契约**对话。

当前 AOSP 公开 API 基线：**API 36**（pin 在 [`ext-runtime/android-stub/android-stub.properties`](ext-runtime/android-stub/android-stub.properties)，换基线只改那一处）。

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

`<AOSP API level>.{提交数/100}.{提交数%100}`；`versionCode = 本仓提交数 + 1000`。

大版本跟着 `android-stub` 的 API 基线走 —— `release.yml` 直接拿 pin 里的 `aospApiLevel` 当大版本，换 pin 它会自动跟着变。后两位是本仓的提交计数。

## 制品与消费

推 main 或手动 dispatch 后发布到 **GitHub Release 资产**（免鉴权）与 **GitHub Packages**，

| 通道 | tag | 触发方式 |
| --- | --- | --- |
| release | `v<版本名>` | 手动 dispatch |
| beta | `v<版本名>-beta.<run_id>` | 手动 dispatch |
| alpha | `<版本名>-alpha.<run_id>` | 推送 main 自动（只出 jar 与两份 JRE，不发 Packages）／手动 dispatch |

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

英文版：[`docs/en/README.md`](docs/en/README.md)。

`docs/agent/` 是面向维护者（含 AI agent）的施工文档，不参与对外说明。
契约型文档（现在是什么样、改的时候要注意什么）在 `docs/agent/`，方案与计划在 `docs/agent/plans/`，
两层各有索引（[`docs/agent/README.md`](docs/agent/README.md)、[`docs/agent/plans/README.md`](docs/agent/plans/README.md)）：

- [`docs/agent/sandbox.md`](docs/agent/sandbox.md) — 沙盒加载 / 转换 / 驱动的现状与踩坑清单
- [`docs/agent/reference-implementations.md`](docs/agent/reference-implementations.md) — 与两个参考实现（Suwayomi-Server / Mihon）的口径差异，`org/json` / `quickjs` 为什么没有可换的 Kotlin 实现
- [`docs/agent/plans/extraction-plan.md`](docs/agent/plans/extraction-plan.md) — 剥离施工计划
- [`docs/agent/plans/extraction-record.md`](docs/agent/plans/extraction-record.md) — 剥离落地记录（目录布局、JRE 归属、验收矩阵等）
- [`docs/agent/plans/api-baseline-upgrade.md`](docs/agent/plans/api-baseline-upgrade.md) — 换 Android 公开 API 基线的一次性记录
- [`docs/agent/plans/java-kotlin-survey.md`](docs/agent/plans/java-kotlin-survey.md) — Java → Kotlin 迁移与 Rust 侧抽离的盘点结论
- [`docs/agent/plans/rust-handoff.md`](docs/agent/plans/rust-handoff.md) — config 抽离给 Rust（已做）+ SQLite / 偏好落盘的归属评估
- [`docs/agent/plans/stub-slimming.md`](docs/agent/plans/stub-slimming.md) — 让最终 jar 不打包无用库：排除集、收益与验证闸门（P0 已落地，45.91 → 24.83 MiB）
- [`docs/agent/plans/modelpack-compat.md`](docs/agent/plans/modelpack-compat.md) — TachiyomiX 图像增强插件（modelpack）是什么、桌面沙盒今天拿它们怎么办（实测两道坎）、以及若要支持差距在哪一层
- [`docs/agent/plans/image-enhancement-rust.md`](docs/agent/plans/image-enhancement-rust.md) — 图像增强能否搬到 Rust 侧：上游的 HTTP 后处理接缝、Rust 侧「契约在行为不在」、推理引擎选型、zip 资源包与模型仓库、许可证约束
