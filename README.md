# Suwayomi-ext-runtime

扩展运行时：**一份共享源码树**（Mihon/Tachiyomi 扩展 API 的实现 + 沙盒路由/驱动），加**桌面 JVM 沙盒宿主**。

从 [`576576/Suwayomi-next`](https://github.com/576576/Suwayomi-next) 剥离而来：这两个部分与 Rust 服务端没有代码依赖，只通过一个 **HTTP + JSON 契约**对话，所以拆成独立仓库各自演进。

> 状态：**剥离已完成**（2026-09-24）。模块目录 `ext-runtime/` 已导入，Suwayomi-next 侧的
> `jvm-sandbox/` 与 `extension-runtime/` 已删除，两条消费链都改从本仓库的 Release 资产取。
> 施工计划见 [`docs/EXTRACTION_PLAN.md`](docs/EXTRACTION_PLAN.md)，
> 决策与试错记录见 [`docs/EXTRACTION_RECORD.md`](docs/EXTRACTION_RECORD.md)。

---

## 目标布局

```
ext-runtime/
├── src/main/kotlin/sandbox/     桌面独有：APK→dex2jar→ASM 修字节码→ChildFirst 类加载
│                                 + 零依赖 HttpServer 宿主（Main.kt）
├── src/main/resources/          server-reference.conf
├── src/test/kotlin/sandbox/     单元测试
├── src/shared/kotlin/           ★ 共享源码树（原 Suwayomi-next/extension-runtime）
│   ├── eu/kanade/tachiyomi/**   扩展 API 实现（HttpSource / ParsedHttpSource / network / model / Filter）
│   ├── suwayomi/tachidesk/**    rx 桥等
│   └── sandbox/**               Router / SourceDriver / Json / Http / Reflect / Errors / FilePreferences / SourceRegistry
├── android-compat/              vendor：android.* / androidx.* 的桌面桩实现（含 Config 子模块）
└── android-stub/                构建期按 pin 下载并剥离的 AOSP 公开 API 空壳
```

**为什么共享源码树是两个源根而不是一个**：桌面独有的 10 个文件依赖 AndroidCompat、ASM、dex2jar、apk-parser、`androidx.preference` 桩与 `com.sun.net.httpserver`，这些在 Android 上**编译不了**；而 Android 宿主（`Suwayomi-next/android/extension-host`）需要的是 `eu.kanade.tachiyomi.**` 那一份。两侧编译**同一份源码**，避免两份实现漂移。

## 构建

```bash
./gradlew build          # 编译 + 测试 + 校验 android-stub 与 android-compat 无同名类
./gradlew jar            # fat jar → build/libs/ext-runtime.jar
```

- 需要 **JDK 25**（`jvmToolchain(25)`，与 CI 的 Temurin 25 一致）。
- 首次构建会从 `dl.google.com` 下载 AOSP 公开 API 包（`platform-30_r03.zip`，约 52 MB），按 `android-stub.properties` 里的 sha256 校验后剥离生成 `android-stub`。可用 `-PaospPackageUrl=<镜像>` 换源，pin 仍然生效。
- 产物里的 `META-INF/android-stub.properties` 记录了这次用的是哪份 AOSP 基线（版本号 = `<api>.<包修订>.<剥离修订>`）。

## 制品与消费方式

打 `v<V>` tag（或手动触发 `publish.yml`）后，两个制品**同时**发到 **GitHub Release 资产**和
**GitHub Packages（Maven）**：

| 制品 | 用途 |
|---|---|
| `ext-runtime-<V>.jar` | **桌面 / 服务端 / Docker**：fat jar，部署为 `<发布根>/bin/ext-runtime.jar`，由 Suwayomi-next 的 server 拉起 |
| `ext-runtime-<V>-shared-sources.jar` | **Android**：`Suwayomi-next/android/extension-host` 展开成目录后作为额外源根编译 |

**消费方走 Release 资产，不走 Packages。** Release 资产**免鉴权**，`curl -fLO` 即可；
Packages 即使对公开包也要求 token，跨仓库还要单独配 PAT。两条链最终都要「下载 + 展开/拷贝」，
Maven 坐标带来的解析能力一点也用不上，没必要为此多一个 secret 和它的过期风险。
（Packages 通道照常发布，留给想按坐标消费的人；目前没有调用方依赖它，所以
`EXT_RUNTIME_TOKEN` 这个 secret **不需要配置**。）

> **Android 侧只能吃 sources，不能吃编译好的 jar。** `:extension-host` 由 AGP 内置的 Kotlin 2.3.20 编译，而本仓库用 2.4.0 —— 2.3.20 读不了 2.4 产出的 Kotlin 元数据。吃源码则各端用自己的编译器，元数据版本问题不存在，且保住「一份源码两端编译」。

需要按 Maven 坐标消费时（Packages 通道）：

```
https://maven.pkg.github.com/576576/Suwayomi-ext-runtime
com.github.576576.suwayomi-ext-runtime:ext-runtime:<version>
```

GitHub 的 **Maven / Gradle 注册表只支持「仓库级权限」**，没有 "Manage Actions access"
入口（那个只存在于 Container / npm / NuGet / RubyGems），所以跨仓库拉取只能用
**PAT (classic) + `read:packages`**。另外两个都不行：`GITHUB_TOKEN` 只能访问工作流所属
仓库的 package；`gh auth login` 的 OAuth token scope 里没有 `read:packages`。

## 与 Suwayomi-next 的关系

- **运行时契约不变**：HTTP 路由（`/health`、`/extensions`、`/sources`、`/reload`、`/source/{id}/…`）、JSON 形状、主类 `sandbox.MainKt`、部署文件名 `bin/ext-runtime.jar` 都按既有约定；Rust 侧 `crates/suwayomi-domain/src/source/sandbox.rs` 不需要改动。
- **本地调试不用发版**：`SUWAYOMI_SANDBOX_JAR` 指向本仓库 `build/libs/ext-runtime.jar` 即可接管。
- **改动的传播**：改了 `src/shared/` → 本仓库打 tag 发版 → Suwayomi-next bump 版本。**这一步漏了不会报错**（Android 会用旧源码），所以别漏。

## 许可

本仓库采用 **Mozilla Public License 2.0**（见 [`LICENSE`](LICENSE)）—— 与上游 [`Suwayomi-next`](https://github.com/576576/Suwayomi-next) 一致。

第三方出处与署名：

| 部分 | 出处 | 许可 |
|---|---|---|
| `ext-runtime/android-compat/` | AndroidCompat（android.* / androidx.* 的桌面桩实现），vendor 为源码 | MPL-2.0 |
| `ext-runtime/src/shared/kotlin/eu/kanade/tachiyomi/` | Mihon / Tachiyomi 的扩展 API 形状（**重写实现**，非拷贝） | Apache-2.0，Copyright 2015 Javier Tomás |
| `ext-runtime/android-stub/` | AOSP 公开 API（构建期按 pin 下载并剥离，不入库） | Apache-2.0 |
