# jvm-sandbox & extension-runtime 剥离计划（v2）

> 源仓库：`576576/Suwayomi-next`（`E:\Github\Suwayomi-next`）  
> 目标仓库：`576576/Suwayomi-ext-runtime`（本仓）  
> 状态：**方案已定，待确认后执行**。本文只定方案，尚未改动任何文件。  
> v1 → v2：回答「extension-runtime 能否完全去除」「能否发 package」两个问题；锁定拍平 / 改名 / 清无效文件 / 修 CI 陷阱四项决策。

---

## 0. v1 → v2 变更摘要

| 项                       | v1                   | v2（本轮）                                          |
| ----------------------- | -------------------- | ----------------------------------------------- |
| 仓库根                     | 待定                   | **拍平**（`.git` 上移一层）                             |
| 模块名                     | `sandbox-desktop/`   | **`ext-runtime/`**                              |
| 制品名                     | 保持 `jvm-sandbox.jar` | **改为 `ext-runtime.jar`**                        |
| `extension-runtime/` 目录 | 作为兄弟目录保留             | **彻底去除**，拍散进 `ext-runtime/src/shared/kotlin`    |
| 消费方式                    | git submodule        | **本仓发 package（GitHub Release 资产）**，不用 submodule |
| 无效文件                    | 仅在风险里提了一句            | 独立清单（§6）                                        |
| CI 陷阱                   | 泛泛列了 3 条             | 12 条逐条给修法（§5）                                   |

---

## 1. 问题一：原 `extension-runtime/` 能否完全去除？

### 结论：**能，且零风险。**

三条实测证据：

1. **依赖方向是单向的。** 桌面侧 10 个文件引用共享侧（如 `Main.kt` 引用 `eu.kanade.tachiyomi.source.PreferenceStores`）；共享侧**零**引用桌面侧符号 —— 逐符号扫了 `AndroidEnv / BytecodeFixer / DexTranslator / ExtensionLoader / ExtensionPreferences / ExtensionRegistry / HttpExchangeExt / InjektSetup / MainKt`，唯一命中是 `sandbox/SourceRegistry.kt:4` 的**注释文字**（在解释「桌面 `ExtensionRegistry` 走 dex2jar，Android 走 PackageManager」）。反向还发现 `LoadedSource` 其实**声明在共享侧**（`sandbox/Reflect.kt:11`）。
2. **两侧 `sandbox` 包无同名符号。** 逐符号比对（附录 A）：桌面 30 个 vs 共享 30 个，交集为空。因为 Kotlin 同包不需要 `import`，这是必须核的隐式耦合，已核实不存在。
3. **Android 侧只吃共享那部分。** 桌面独有 10 个文件的第三方依赖全是桌面专属的：
   | 文件                                        | 桌面专属依赖                                                                           |
   | ----------------------------------------- | -------------------------------------------------------------------------------- |
   | `AndroidEnv.kt` / `InjektSetup.kt`        | `xyz.nulldev.androidcompat.**`（AndroidCompat vendor 源码）                          |
   | `BytecodeFixer.kt`                        | `org.objectweb.asm.**`                                                           |
   | `DexTranslator.kt` / `ExtensionLoader.kt` | `com.googlecode.d2j` / `dex2jar`、ASM                                             |
   | `ExtensionRegistry.kt`                    | `net.dongliu.apk.parser.ApkFile`                                                 |
   | `ExtensionPreferences.kt`                 | `androidx.preference.**`（来自 `android-compat/src/main/java/androidx/preference/`） |
   | `HttpExchangeExt.kt` / `Main.kt`          | `com.sun.net.httpserver`（Android 无此模块）                                           |
   | `BytecodeCompat.kt`                       | 无第三方依赖，但只被上述文件使用                                                                 |
   → 这些文件**在 Android 上编译不了**，所以两个源根必须分开。合并成一个源根不可行。

### 做法（推荐 **B**）

```
ext-runtime/                        ← 原 jvm-sandbox（改名）
├── build.gradle.kts
├── src/main/kotlin/sandbox/        ← 桌面独有 10 个文件（原样，不动）
├── src/main/resources/             ← server-reference.conf（原样，不动）
├── src/test/kotlin/sandbox/        ← 5 个测试（两个源根都可见）
├── src/shared/kotlin/              ← ★ 原 extension-runtime/src/main/kotlin 整体上提
│   ├── eu/kanade/tachiyomi/**
│   ├── suwayomi/tachidesk/**
│   └── sandbox/{Errors,FilePreferences,Http,Json,Reflect,Router,SourceDriver,SourceRegistry}.kt
├── android-compat/
└── android-stub/
```

Gradle 侧**只加一行**：

```kotlin
sourceSets["main"].kotlin.srcDir("src/shared/kotlin")
```

Android 侧把源目录指到 `…/ext-runtime/src/shared/kotlin`。  
**`extension-runtime/` 目录彻底消失**，且 `src/main/kotlin` 仍是模块本体代码（符合 Gradle 惯例）、`src/main/resources` 不用搬。

### 备选（都不推荐，列出备查）

| 方案 | 内容                                                   | 为什么不选                                                                                                                                                                                       |
| -- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A  | `src/main/kotlin` 放**共享**、`src/desktop/kotlin` 放桌面独有 | Android 侧路径更"标准"（落在 `src/main/kotlin`），但桌面侧要把 `server-reference.conf` 从 `src/main/resources` 搬到 `src/desktop/resources`，且模块本体的代码离开了 `main` —— 改动更大、语义更绕                                     |
| C  | 共享部分独立成 `:runtime` Gradle 子项目                        | 能独立发布、能独立跑测试；但依赖清单要在两个子项目里各写一份（**漂移风险**，而「一份源码两端编译」正是当初抽 `extension-runtime` 的目的），且 `android-stub`（要扫共享源码算去重集合）与 `:runtime`（`compileOnly(project(":android-stub"))`）互相引用，Gradle 配置更绕。列为后续演进 |

---

## 2. 问题二：消费方式能否为本仓发 package？

### 结论：**能，而且可以彻底不要 submodule。** 但必须发**两种**制品。

| 消费者                       | 需要的制品                                  | 在 Suwayomi-next 侧的用法                                                                          |
| ------------------------- | -------------------------------------- | --------------------------------------------------------------------------------------------- |
| 桌面（`bin/ext-runtime.jar`） | `ext-runtime-<ver>.jar`（fat jar）       | 下载后直接 `cp` 进 `<STAGE>/bin/ext-runtime.jar`，**不再本地跑 gradle**                                   |
| Android `:extension-host` | `ext-runtime-<ver>-shared-sources.jar` | 下载后 `unzip -q -d build/ext-runtime-src`，再 `addStaticSourceDirectory("build/ext-runtime-src")` |

`shared-sources.jar` 用自定义任务产出（3 行）：

```kotlin
val sharedSourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("shared-sources")
    from("src/shared/kotlin")
}
```

### 2.1 分发通道对比

| 通道                     | 下载是否要凭据                 | 备注                                                                                              |
| ---------------------- | ----------------------- | ----------------------------------------------------------------------------------------------- |
| **GitHub Release 资产**  | **不要**                  | 推荐。`curl -fsSL -o` 即可；与现有 `webui_url` 的模式完全一致（`release.yml` 的 `prep` job 解析一次 URL，所有 target 复用） |
| GitHub Packages（Maven） | **要**（public 包也要 token） | 坐标 `https://maven.pkg.github.com/576576/Suwayomi-ext-runtime`；多一份凭据管理，换不来额外好处                   |
| JitPack                | 不要                      | 本仓打 tag 后可直接按坐标消费，但它在**自己的容器里跑构建**（要下 52MB AOSP 包、有构建时长上限）→ 不稳                                  |
| Maven Central          | —                       | 要签名 + 域名验证，对自用项目过重                                                                              |

→ **选 GitHub Release 资产**，并复用 `release.yml` 里已有的「prep 解析 URL → 传给 build」模式。

### 2.2 一条硬约束：Android 只能吃 sources，不能吃编译好的 jar

`:extension-host` 由 AGP 9.2.1 **内置的 Kotlin 2.3.20** 编译，而桌面沙盒用 **Kotlin 2.4.0**。

- 若 Android 改吃**编译好的 jar**：2.3.20 编译器读不了 2.4 产出的 Kotlin 元数据（`class file was compiled with a newer version of Kotlin`）。要吃就得把共享部分的编译版本降到 2.3.20 + `-Xcontext-parameters` —— 那就推翻了 `docs/migration/ANDROID_IMPL.md` 的 A4/D2 决策（「同一份源码、两端各自编译」）。
- 吃 **sources.jar**：Android 仍用自己的 2.3.20 编译这份源码，**元数据版本问题不存在**，且保留了「改一行源码，两端行为同步」的性质。

→ 所以 `shared-sources.jar` 不是「退而求其次」，而是**唯一正确的制品形态**。

### 2.3 package 化的真实代价（必须接受）

1. **构建顺序耦合**：必须先在本仓 tag/发版，Suwayomi-next 才能构建。以前「一个 commit 全绿」，现在变成两步。
2. **本地开发闭环变长**：改沙盒 → 本仓打 tag（或 `publishToMavenLocal`）→ Suwayomi-next 拉取。可用 `mavenLocal()` + `-SNAPSHOT` 缓解；也可直接用既有的 `SUWAYOMI_SANDBOX_JAR` 环境变量指向本地构建的 jar 来做本地调试（**这条今天就有，不用新加代码**）。
3. **换来的好处**（很实在）：v1 里最危险的三个 CI 陷阱（submodule 未 init、Docker 上下文不含 submodule、`.dockerignore` 误伤）**全部消失**；`Dockerfile` 的 `sandbox` stage 整段删除（改成 `curl` 下载），镜像构建更快、不需要在构建容器里装 gradle。

---

## 3. 已锁定的决策

| #  | 决策                                             | 落地                                                                                       |
| -- | ---------------------------------------------- | ---------------------------------------------------------------------------------------- |
| L1 | **git root 拍平**                                | `.git` 从 `Suwayomi-ext-runtime/Suwayomi-ext-runtime/` 上移到 `Suwayomi-ext-runtime/`，与工作区对齐 |
| L2 | **模块改名** `jvm-sandbox/` → `ext-runtime/`       | 见 §4.1                                                                                   |
| L3 | **制品改名** `jvm-sandbox.jar` → `ext-runtime.jar` | 全部改名点见 §4.2                                                                              |
| L4 | **`extension-runtime/` 完全去除**                  | 拍散进 `ext-runtime/src/shared/kotlin`（§1）                                                  |
| L5 | **消费方式 = 本仓发 package**                         | GitHub Release 资产，不用 submodule（§2）                                                       |
| L6 | **清掉无效文件**                                     | §6                                                                                       |
| L7 | **修 CI 失败陷阱**                                  | §5                                                                                       |

---

## 4. 命名与改名点

### 4.1 新名字

| 对象                | 新值                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------- |
| 仓库                | `Suwayomi-ext-runtime`                                                                                        |
| Gradle 根工程        | `suwayomi-ext-runtime`                                                                                        |
| 模块目录              | `ext-runtime/`                                                                                                |
| 子项目               | `:ext-runtime`、`:ext-runtime:android-compat`、`:ext-runtime:android-compat:config`、`:ext-runtime:android-stub` |
| 构建产物              | `ext-runtime.jar`（fat jar）                                                                                    |
| 共享源码制品            | `ext-runtime-<ver>-shared-sources.jar`                                                                        |
| 发布资产名             | `ext-runtime-<ver>.jar` / `ext-runtime-<ver>-shared-sources.jar`                                              |
| 部署文件名             | `<发布根>/bin/ext-runtime.jar`                                                                                   |
| 主类                | `sandbox.MainKt`（**不变** —— 运行时契约）                                                                             |
| HTTP 路由 / JSON 契约 | **不变**                                                                                                        |

### 4.2 制品改名涉及的全部位置（逐文件逐行）

**A. 本仓（新仓库）**

| 位置                             | 改动                                                                                                         |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| `settings.gradle.kts`          | `rootProject.name = "suwayomi-ext-runtime"`；`include("ext-runtime")` 等四个                                   |
| `ext-runtime/build.gradle.kts` | `tasks.jar { archiveFileName.set("ext-runtime.jar") }`；新增 `sharedSourcesJar`；`srcDir("src/shared/kotlin")` |
| `.github/workflows/build.yml`  | 上传两个制品 + `.sha256`                                                                                         |

**B. Suwayomi-next（Rust 源码）**

| 位置                                                                                                                                                                                        | 改动                                                                                                     |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `crates/suwayomi-server/src/lib.rs:132,145`                                                                                                                                               | `resolve_sandbox_jar()`：`dir.join("ext-runtime.jar")` / `dir.join("bin").join("ext-runtime.jar")`；注释同步 |
| `crates/suwayomi-server/src/main.rs:54`                                                                                                                                                   | 注释                                                                                                     |
| `crates/suwayomi-domain/src/source/sandbox.rs:1,719`、`crates/suwayomi-graphql/src/query.rs:2594`、`settings.rs:686`、`crates/suwayomi-android/src/lib.rs:160`、`suwayomi-tray/src/main.rs:4` | 注释                                                                                                     |

**C. Suwayomi-next（构建 / 打包）**

| 位置                                             | 改动                                                                                                                              |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `.github/workflows/build.yml:110,162-163`      | 删掉 `cd jvm-sandbox && ./gradlew -q jar`，改为 `cp "$EXT_RUNTIME_JAR" "$STAGE/bin/ext-runtime.jar"`（`EXT_RUNTIME_JAR` 由 prep 解析后传入） |
| `.github/workflows/build.yml:416`              | 冒烟断言 `test -f /opt/suwayomi/bin/ext-runtime.jar`                                                                                |
| `.github/workflows/release.yml:90-116`         | `prep` job 新增解析 `ext_runtime_url`（照 `webui_url` 的写法）                                                                            |
| `Dockerfile:14,48-57,106-107`                  | **删除 `sandbox` stage 整段**；改为在运行镜像里 `curl` 下载并落到 `/opt/suwayomi/bin/ext-runtime.jar`（或新增一个极薄的 `curl` stage）                      |
| `build.bat:12,41-43,56`                        | 删掉本地 gradle 步骤，改为从本仓取 jar（或加 `-UseLocalSandbox` 开关指向本仓 `build/libs`）                                                            |
| `.dockerignore:3-4`                            | 注释；**「`jvm-sandbox/`、`extension-runtime/` 必须留着」整段删除**                                                                           |
| `.gitignore`                                   | 删 `jvm-sandbox/**/build/` 一行                                                                                                    |
| `android/extension-host/build.gradle.kts:4,54` | 指向解压后的 `build/ext-runtime-src`                                                                                                  |
| `scripts/make-jre.sh:383`                      | 注释（`extension-runtime` → `ext-runtime/src/shared/kotlin`）                                                                       |

**D. 文档**

`README.md:42,80,102-112`、`docs/en/README.md:46,95,126-138`、`docs/release.md:64,96,114,123`、`docs/migration/MIGRATION_PLAN.md:76,278-284,399-400,431`、`docs/migration/ANDROID_IMPL.md:39,101-126,205,248`、`docs/migration/MIGRATION_STATUS.md:65,127,141,148`。

**E. 本地工具（`.workbuddy/verify/`）**

| 脚本                   | 行                               |
| -------------------- | ------------------------------- |
| `deploy_latest.py`   | 26, 29                          |
| `ext_probe.py`       | 36, 121, 138                    |
| `ci_equiv.py`        | 244, 247                        |
| `ci_pack_check.py`   | 197, 201, 375, 384-388, 711-718 |
| `eval_stub_dedup.py` | 14（硬编码 ROOT）                    |
| `run_verify.sh`      | 14                              |
| `pull_release.py`    | 78                              |

---

## 5. CI / 部署失败陷阱与修法

> 原则：**凡是「改错了不报错、只是悄悄少了个东西」的，都要加断言。**

| #       | 陷阱                                                                                                                            | 后果                                                                                     | 修法                                                                                                                        |
| ------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| **T1**  | 新仓库 `.gitattributes` 缺 `gradlew text eol=lf`                                                                                  | Linux runner 上 wrapper 的 shebang 带 `\r` → `bad interpreter: No such file or directory` | P1 就写入完整规则（`gradlew`/`*.sh`/`*.kt`/`*.md`/`*.yml` = LF，`*.bat` = CRLF），并用 `git check-attr eol -- gradlew` 自检              |
| **T2**  | ~~`actions/checkout` 漏 `submodules: recursive`~~                                                                              | —                                                                                      | **package 通道下此陷阱不存在**（L5 的附带收益）                                                                                           |
| **T3**  | ~~Docker 上下文不含 submodule~~                                                                                                    | —                                                                                      | 同上，`sandbox` stage 直接删除                                                                                                   |
| **T4**  | `release.yml` prep 解析出的资产 URL 为空 / 404                                                                                        | CI 里 `curl` 静默下到 0 字节文件或 HTML 错误页 → 打包出一个坏 jar                                         | prep 里 `curl -fsSL` + 落地后 `sha256sum -c`（本仓 Release 同时发 `*.sha256`）；再断言 jar 非空且 `unzip -l` 能列出 `sandbox/MainKt.class`     |
| **T5**  | Android 源目录指错 / 为空，AGP **不报错**                                                                                                | 编译期报 `Unresolved reference`（可发现）；若目录存在而内容不全 → **静默少类**                                 | `:extension-host` 加断言任务：解压后校验 `build/ext-runtime-src/eu/kanade/tachiyomi/source/Source.kt`、`sandbox/Router.kt` 等关键文件存在    |
| **T6**  | `ci_pack_check.py:384-388` 的「`.dockerignore` 没排除 X 目录」断言                                                                      | 目录改名后该断言**恒为真**，变成永远通过的空断言                                                             | 同步改新目录名；**package 通道下这段断言应整段删掉**（不再需要那两个目录进上下文）                                                                           |
| **T7**  | `ci_pack_check.py:717-718` 断言产物含 `bin/jvm-sandbox.jar`                                                                        | 改名后**恒为假**（CI 直接红 —— 这是好事）                                                             | 改成 `bin/ext-runtime.jar`                                                                                                  |
| **T8**  | `eval_stub_dedup.py:14` 硬编码 `E:/Github/Suwayomi-next/jvm-sandbox`                                                             | 本地去重校验读到空目录 → 报「无同名类」**假阳性**                                                           | 路径指向本仓；并加「扫描到的类数 > 0」断言                                                                                                   |
| **T9**  | `ci_equiv.py:244-247` 伪造 `jvm-sandbox/gradlew`                                                                                | 路径改名后伪造失效，CI 等价性验证失去意义                                                                 | 同步改名；package 通道下改为伪造「下载好的 jar」                                                                                            |
| **T10** | 桌面 6 个 target 各自下载 52MB AOSP 包，**无缓存、无重试**                                                                                    | 一次网络抖动挂掉整个 release                                                                     | 加 `gradle/actions/setup-gradle@v4`（或 `actions/cache` 缓存 `~/.gradle`）；AOSP 下载加重试（`prepareAospJar` 已有 sha256 校验，可安全重试）      |
| **T11** | `resolve_sandbox_jar()` 的两处**静默失败**：① `SUWAYOMI_SANDBOX_JAR` 设了但文件不存在 → 直接往下走；② 找不到 jar → `SandboxMode::Disabled`，**一条日志都不打** | 「沙盒没起来」表现为「扩展列表空的」，排查要从 server 日志反推                                                    | ① 显式设了路径但 `!is_file()` → `warn!` 带路径；② 落到 `Disabled` → `warn!("未找到扩展沙盒 jar（ext-runtime.jar），扩展功能不可用")`。**两处都是纯增量日志，不改行为** |
| **T12** | 本仓 `verifyStubDedup` 挂在 `tasks.jar` 的 `dependsOn` 上                                                                           | 本仓 CI 若只跑 `./gradlew test`，这个校验根本不会执行                                                  | 本仓 CI 跑 `./gradlew build`（或显式 `test jar verifyStubDedup`）                                                                 |


## 7. 分阶段施工

### P0 · 前置

1. 停干净后台进程（`latest_proc.py --stop`、`ext_probe.py --stop`），`netstat` 核 8090/8091/4599/18899 无监听。
2. 记录基线：现 `suwayomi-jvm-sandbox.jar` 的 md5、`META-INF/android-stub.properties`、`./gradlew test` 结果。**迁移后逐项比对。**
3. `pip install git-filter-repo`（装进受管 venv）。
4. 确认本仓远端 Release 资产可被 Suwayomi-next 的 CI **匿名** `curl` 到。

### P1 · 拍平 + 骨架

```bash
mv E:/Github/Suwayomi-ext-runtime/Suwayomi-ext-runtime/.git E:/Github/Suwayomi-ext-runtime/.git
# .gitattributes 一并上移；git status 应为干净
```

新建 `settings.gradle.kts`、`README.md`、`LICENSE`、`.gitattributes`（**T1**）、`.gitignore`、`docs/`、`.github/workflows/build.yml`。  
**验收**：`git status` 干净；`git check-attr eol -- gradlew` → `lf`。

### P2 · 历史抽取

```bash
git clone --no-local E:/Github/Suwayomi-next /tmp/sn-split
cd /tmp/sn-split && git filter-repo \
  --path jvm-sandbox/ --path extension-runtime/ \
  --path-rename jvm-sandbox/:ext-runtime/ \
  --path-rename extension-runtime/:ext-runtime/src/shared/
cd <本仓> && git remote add split /tmp/sn-split && git fetch split && git merge --allow-unrelated-histories split/main
```

> 两条 `--path-rename` 一条命令同时完成「模块改名」与「共享源码拍散」：`extension-runtime/src/main/kotlin/...` → `ext-runtime/src/shared/kotlin/...`。  
> 回退：filter-repo 不可用时改为文件拷贝 + 单次 import 提交（提交信息里记源 commit）。

**验收**：`git log --follow ext-runtime/src/shared/kotlin/sandbox/Router.kt` 能追到改名前的提交。

### P3 · 本仓独立自洽（**第一个硬门禁**）

1. `settings.gradle.kts` 四个 `include`。
2. wrapper 上移到仓库根；`ext-runtime/` 内三个 `build.gradle.kts` 的相对路径按新布局调整：
   - `build.gradle.kts`：`srcDir("src/shared/kotlin")`
   - `android-stub/build.gradle.kts:64,148`：`file("../src/shared/kotlin")`
   - `android-stub/build.gradle.kts:62,146`：`../android-compat/...` **不变**
3. 保留 `verifyStubDedup`、fat jar 逻辑、`mainClass = "sandbox.MainKt"`、`jvmToolchain(25)`、`application` 插件全部原样；`tasks.jar` 改 `archiveFileName`。
4. 新增 `sharedSourcesJar`。
5. 本仓 CI：JDK 25（temurin）+ `./gradlew build`（**T12**，带上 `verifyStubDedup`）；tag 时发两个制品 + `.sha256`。

**验收**：

```bash
./gradlew test jar verifyStubDedup
unzip -p build/libs/ext-runtime.jar META-INF/android-stub.properties   # 与 P0 基线逐字段一致
unzip -l build/libs/ext-runtime-shared-sources.jar | head              # 含 eu/kanade/tachiyomi/**
```

### P4 · Suwayomi-next 改造

按 §4.2 的 B/C/D/E 四组改动 + §5 的 T4/T5/T6/T7/T8/T9/T11。  
建议分三个提交：① 删除 + 路径改名；② CI/Docker/打包切到 package 通道；③ 文档与本地脚本。

### P5 · 端到端验收（§8 矩阵）

### P6 · 收尾

1. 本仓打 tag（如 `v0.1.0`），Suwayomi-next 侧把 `ext_runtime` 版本钉到该 tag。
2. 记忆搬迁：`Suwayomi-next/.workbuddy/memory/DETAILS.md` 的「扩展加载（桌面沙盒 · issue #7）」整节（含 `CONVERTER_VERSION`、四种加载失败成因、`javap` 定位法、`source_visible_check.mjs`）→ 本仓 `.workbuddy-ai/memory/`；Suwayomi-next 侧留一行指路。
3. 写明日常约定：**改沙盒 → 本仓 tag → Suwayomi-next bump 版本**（否则 Android 用的是旧源码，且不报错）。
4. 后台进程停干净。

---

## 8. 验收矩阵

| #   | 项          | 命令 / 判据                                                                               | 通过标准                                                                     |
| --- | ---------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| V1  | 本仓独立构建     | `./gradlew build`                                                                     | 与 P0 基线一致                                                                |
| V2  | AOSP 溯源    | `unzip -p build/libs/ext-runtime.jar META-INF/android-stub.properties`                | 逐字段与基线一致                                                                 |
| V3  | 桩去重        | `verifyStubDedup`                                                                     | 无同名类，**且扫描类数 > 0**（T8）                                                   |
| V4  | 共享源码制品     | `unzip -l ...shared-sources.jar`                                                      | 含 `eu/kanade/tachiyomi/source/Source.kt` 等关键文件，且**不含** `sandbox/Main.kt` |
| V5  | 沙盒脱离服务端跑   | `.workbuddy/verify/ext_probe.py --root E:/Github/Suwayomi-builds/ext-lab`（4599）       | `/health` 通、扩展列表非空                                                       |
| V6  | **真实扩展执行** | 同上 + `source_visible_check.mjs <图源名>`                                                 | 搜索 / 章节 / **筛选页**（`/source/{id}/filters`）真实返回                            |
| V7  | 桌面端到端      | `deploy_latest.py` → 后台启动 → CDP 脚本                                                    | 8090 WebUI 可访问、源可见可读；`<实例>/bin/ext-runtime.jar` 的 md5 == 本仓构建产物          |
| V8  | Docker     | `docker build` + `ci_pack_check.py`                                                   | `/opt/suwayomi/bin/ext-runtime.jar` 存在；容器起得来                             |
| V9  | Android    | `cd android && ./gradlew :app:assembleRelease`                                        | APK 产出；解压目录校验任务通过（T5）                                                    |
| V10 | 静默失败已修     | 故意设 `SUWAYOMI_SANDBOX_JAR=/不存在` 启动                                                    | 日志出现明确的 warn（T11）                                                        |
| V11 | Rust 侧     | `cargo build --release -p suwayomi-server` + `cargo clippy --workspace --all-targets` | 编译过、零告警                                                                  |

> V5/V6 是本次剥离真正的价值验证：能脱离 Rust 服务端单独起沙盒，说明本仓确实自洽；能加载真实扩展，说明共享源码树搬对了。

---

## 9. 风险与回滚

| #  | 风险                                       | 影响                                            | 对策                                                   |
| -- | ---------------------------------------- | --------------------------------------------- | ---------------------------------------------------- |
| R1 | 构建顺序耦合（package 通道固有）                     | 本仓没发版 → Suwayomi-next 构建失败                    | prep 里显式解析 + sha256 校验（T4），失败即红，不静默                  |
| R2 | 共享源码漂移                                   | 本仓改了 `src/shared`，Suwayomi-next 用的还是旧 sources | 版本号是 tag，`prep` 输出里打印实际用的版本；P6.3 写成明文约定              |
| R3 | 本地开发闭环变长                                 | 调试摩擦                                          | 保留 `SUWAYOMI_SANDBOX_JAR` 指向本地构建的 jar 这条既有通道（不改一行代码） |
| R4 | `android-stub` 首次构建下载 52MB               | CI 慢 / 网络抖动挂                                  | T10：gradle 缓存 + 重试 + `aospPackageUrl` 可换镜像           |
| R5 | filter-repo 的路径映射写错                      | 历史错位（不致命，但脏）                                  | P2 后立即用 `git log --follow` 抽查两个文件（一个桌面的、一个共享的）       |
| R6 | 改名遗漏                                     | 漏一处 = 沙盒静默不启动                                 | §4.2 清单 + V7 的 md5 比对 + V10 的 warn 兜底                |
| R7 | 两侧 `sandbox` 包同名                         | 误判会冲突                                         | 已核实无同名符号（附录 A），且本次是「分两个源根」，与现状等价                     |
| R8 | `.workbuddy/verify/artifacts/`（历史发布包）占空间 | 磁盘                                            | §6.2 列出，等你确认后再动                                      |

**回滚**：本仓 tag 与历史独立存在；Suwayomi-next 只需把 `ext_runtime` 版本改回上一个 tag，或临时用 `SUWAYOMI_SANDBOX_JAR` 指向旧 jar。删除的 `jvm-sandbox/`、`extension-runtime/` 仍在 Suwayomi-next 的历史 commit 里（`git checkout <sha> -- jvm-sandbox extension-runtime`）。

---

## 10. 明确不做的事

- 不改 `sandbox.MainKt` 主类名、HTTP 路由、JSON 契约（Rust 客户端 `sandbox.rs` 不动）。
- 不改 `AndroidEnv.kt:120` 的 `http.agent` 字符串（UA 是运行时行为，会让站点侧看到不同标识）。
- 不把 `android/` 宿主工程搬进本仓（它是 AGP 工程，与 `:app` 同工程）。
- 不把 `make-jre.sh` 搬进本仓（jlink 是发布打包职责）。
- 不把共享部分做成 `:runtime` 子项目（§1 备选 C，列为后续演进）。
- 不借机重写任何沙盒逻辑（`CONVERTER_VERSION`、dex2jar 链路、字节码修复全部原样）。

---

## 附录 A · 两侧 `sandbox` 包符号比对（证明无同名类）

**桌面独有（`ext-runtime/src/main/kotlin/sandbox/`）**  
`BytecodeCompat` `BytecodeFixer` `BytesHierarchy` `ChildFirstURLClassLoader` `DexTranslator` `ExtensionLoader` `ExtensionRegistry` `HttpExchange` `SandboxApp` `buildSourcePreferencesScreen` `charSeqArrayJson` `dialogTitleJson` `ensureDefault` `installHostVersion` `installHttpAgent` `installSandboxContext` `isJarResource` `main` `preferenceList` `preferenceScreenToJson` `preferenceToJson` `registerAndroidCompatConfig` `resolveSourceClass` `setupInjekt` `sourcePreferencesJson` `sourceSharedPreferences` `startMainLooper` `stringArrayJson` `twoStateJson` `writeSourcePreference`

**共享（`ext-runtime/src/shared/kotlin/sandbox/`）**  
`BridgeContinuation` `ExtensionInfo` `FilePreferences` `HttpRequest` `HttpResponse` `LoadedSource` `Router` `SourceDriver` `SourceRegistry` `buildModel` `callGetter` `callMethod` `callSuspendMethod` `collectInterfaceNames` `convert` `errorJson` `filterToMap` `findMethod` `implementsInterface` `interface` `isExtensionOwnMethod` `jsonOpt` `jsonStr` `primitiveOf` `readBaseUrl` `readField` `readHomeUrl` `readSupportsLatest` `readableError` `setField`

**交集：空。**

## 附录 B · 关键相对路径对照

| 文件                                                 | 现在                                                | 迁移后                                               |
| -------------------------------------------------- | ------------------------------------------------- | ------------------------------------------------- |
| `ext-runtime/build.gradle.kts`                     | `srcDir("../extension-runtime/src/main/kotlin")`  | `srcDir("src/shared/kotlin")`                     |
| `ext-runtime/android-stub/build.gradle.kts:64,148` | `file("../../extension-runtime/src/main/kotlin")` | `file("../src/shared/kotlin")`                    |
| `ext-runtime/android-stub/build.gradle.kts:62,146` | `file("../android-compat/src/main/java")`         | **不变**                                            |
| `android/extension-host/build.gradle.kts:54`       | `file("../../extension-runtime/src/main/kotlin")` | `file("build/ext-runtime-src")`（由 sources.jar 解压） |

---

## 11. 待你确认

1. **`ext-runtime.jar`** 这个部署文件名是否合适？备选：`sandbox.jar`、`suwayomi-ext-runtime.jar`。
2. **package 通道**是否确定走 **GitHub Release 资产**（而非 GitHub Packages）？
3. **`ext-runtime/src/shared/kotlin`** 这个共享源根的名字是否合适？备选：`src/shared/`、`src/common/`。
4. §6.1 的删除清单里**哪些你不同意删**？§6.2 的 `.workbuddy/verify/artifacts/` 要不要一起清？

确认后我按 P0 → P6 执行，每阶段回报验收结果。
