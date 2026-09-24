# 剥离实现记录

P2 / P3 落地过程中实际做了什么、为什么这么做、哪些路走不通。
决策与试错的**唯一记录处**；构建脚本里只留结论性一句话，具体论证看这里。

计划本身（决策 L1–L9、陷阱 T1–T13、验收矩阵 V1–V12）见 [`EXTRACTION_PLAN.md`](./EXTRACTION_PLAN.md)。

---

## 1. 目录布局：为什么子项目带 `:ext-runtime:` 前缀

`git filter-repo` 抽出 `jvm-sandbox/` 与 `extension-runtime/` 两条路径后，把前者
`--path-rename` 成 `ext-runtime/`。导入完成后模块目录就是仓库根的 `ext-runtime/`。

原计划是「模块自带 wrapper + 模块级 settings」。**执行时改成了并入根构建**，原因：

- `settings.gradle.kts` 靠目录路径发现子项目。子项目路径一旦变成 `:ext-runtime:android-compat`，
  模块内所有 `project(":android-compat")` / `project(":android-stub")` 全部失效 —— 必须逐个改。
- 模块自带的 `gradlew`、`gradlew.bat`、`gradle/wrapper/*` 与根目录的是**同一 blob**
  （`adff685a…`，Gradle 9.2.1），保留等于两套 wrapper 各自构建一半模块。
  已用 `git rm` 删除，只留根的一套。

改名的连带影响：**Kotlin 模块名默认是 `${group}:${project.name}`**，而 `project.name` 此时
是 `ext-runtime.android-compat` 这类带父项目前缀的名字。这会让 `@Metadata` 注解里的
`d2` 数组跟着漂移，进而体现为 `.kotlin_module` 文件名与上百个 `.class` 的字节变化。

结论：三个 `.kts` 里都显式 `moduleName.set(...)` 钉死：`suwayomi-ext-runtime`、
`suwayomi-ext-runtime:android-compat`、`suwayomi-ext-runtime:android-compat:config`。
钉住之后 `build/libs` 里的模块名不再随坐标/路径变化，diff 噪音归零。

> `android-stub` 不编译 Kotlin 源码，无需 `moduleName`。

---

## 2. 共享源码树为什么必须是独立源根

`ext-runtime/src/shared/kotlin`（51 个 `.kt`，原 `extension-runtime/src/main/kotlin`）
**不能**并进 `src/main/kotlin`，只能作为额外源根存在，且 Android 侧只吃这一个根。

桌面独有的 10 个文件依赖 AndroidCompat / ASM / dex2jar / apk-parser / androidx.preference
桩 / `com.sun.net.httpserver`，这些在 Android 上编译不了；而共享树里的
`eu.kanade.tachiyomi.**` 接口实现、`SourceDriver`、`Router` 必须两端共用，避免实现漂移。

依赖方向是单向的：桌面侧引用共享侧，共享侧对桌面侧**零符号引用**（`SourceRegistry.kt` 里
只有一句注释提到）。两个 `sandbox` 包各 30 个符号，交集为空。

---

## 3. 两个制品、一个 artifactId

`com.github.576576.suwayomi-ext-runtime:ext-runtime` 下挂两个制品：

| 制品 | 内容 | 消费者 |
| --- | --- | --- |
| `ext-runtime.jar` | 桌面沙盒 fat jar（含 `Main-Class`，可 `java -jar`） | Suwayomi-next 服务端 |
| `ext-runtime-shared-sources.jar` | 共享源码树 51 个 `.kt` | Suwayomi-next 的 Android `:extension-host` |

挂在同一个 artifactId 下，是为了让消费方**只配一次**授权/凭据。

**为什么 Android 必须吃源码而不是编译产物**：Android 侧由 AGP 内置 Kotlin 2.3.20 编译，
读不了桌面侧 Kotlin 2.4.0 产出的元数据版本。这是硬约束，不是偏好。

两个制品名都显式固定：`tasks.jar { archiveFileName.set("ext-runtime.jar") }`、
`sharedSourcesJar { archiveFileName.set("ext-runtime-shared-sources.jar") }`。
不固定的话，发布带上 `-PextRuntimeVersion` 后 Gradle 会给 jar 名加版本前缀
（`ext-runtime-0.1.0-shared-sources.jar`），而部署侧（Rust `resolve_sandbox_jar()`、
Dockerfile）按固定名找 —— 版本号只该出现在 Maven 坐标和 Release 资产名里。

`sharedSourcesJar` 还要显式挂进 `assemble`，否则 `./gradlew build` 不产出它，
CI 里「build 之后断言文件存在」会失败。

---

## 4. 构建一致性实测（2026-09-23）

改了 group / 仓库名 / 子项目路径之后，需要证明**产物行为没变**。逐字节 diff 不可行
（`@Metadata` 里的模块名必然不同），所以按语义比对：

对照物：`Suwayomi-next/jvm-sandbox/build/libs/suwayomi-jvm-sandbox.jar`
（基线 commit `888a2be6`，md5 `9ffabd5dbcfeb662726167d7d796c834`）。

比对脚本：[`.workbuddy-ai/baseline/verify-semantic.py`](../.workbuddy-ai/baseline/verify-semantic.py)
（用 `javap -c -p` 反汇编，抹掉模块名与常量池索引后逐方法比对）。

结果：

| 指标 | 值 |
| --- | --- |
| 条目总数 | 24193 = 24193 |
| CRC 不同的 `.class` | 169 |
| 语义一致（逐方法相同） | 167 |
| 仅成员声明顺序不同 | 2 |
| **语义不一致** | **0** |

- 169 个差异全部来自 `@Metadata` 里的模块名。
- 差异的 2 个是 `ConfigurableSource` 与 `ConfigurableSource$DefaultImpls`：编译器生成的
  `access$getLang$jd` / `getLang` 合成访问器**声明位置**不同（旧 jar 放末尾，新放中段），
  方法体逐字节相同。方法顺序不影响 JVM 语义。
- `sandbox/*.class` 50 = 50、`eu/kanade/tachiyomi/` 85 = 85、`suwayomi/tachidesk/` 6 = 6、
  `android/` 3599 = 3599，集合完全一致。
- `META-INF/android-stub.properties` 与 P0 基线**逐字段一致**（`version=30.r03.1`）。

jar 体积 45382030 → 45381739（少 291 字节）＝ 上面两个类的成员表增量（各 +30、+26 字节）
减去若干 `.kotlin_module` 名字变短。

### 试错：比对脚本自身的坑

第一版比对把「类的收尾 `}`」归进了最后一个方法的块，方法顺序一变就误报
「最后一个方法体不同」。**这不是代码差异，是解析器的伪影。**
`verify-semantic.py` 里已丢弃只含 `}` 的行。

> 教训：反汇编比对要先排除「顺序敏感」的东西 —— 常量池索引、成员顺序、尾部花括号。

---

## 5. 认证与消费

发布走本仓库自己的 `GITHUB_TOKEN`（`permissions.packages: write`），不需要额外配置。

**跨仓库拉取**是另一回事：GitHub Packages 的 **Maven / Gradle 注册表只支持仓库级权限**，
没有 "Manage Actions access"（那只存在于 Container / npm / NuGet / RubyGems）。
要用 Packages 跨仓库消费，Suwayomi-next 侧必须配 **PAT (classic) + `read:packages`**，
而且公开包同样需要鉴权。

**但这条 PAT 路最后没走。** 发布时两个制品**同时**挂到了 GitHub Release 资产上，
而 Release 资产**免鉴权**；于是 Suwayomi-next 的两条消费链都改走 Release：

| 消费方 | 资产 | 通道 |
| --- | --- | --- |
| 桌面 / Docker | `ext-runtime-<V>.jar` | Release 资产（免鉴权） |
| Android `:extension-host` | `ext-runtime-<V>-shared-sources.jar` | Release 资产（免鉴权） |

结果是 Suwayomi-next **一个 PAT secret 都不需要**，也没有 PAT 过期导致 401 的风险。
Packages 通道照常发布（`publish.yml` 里 `maven-publish` 那步），作为「想按 Maven 坐标
消费」的备选留着，目前没有调用方依赖它 —— 哪天真要用，再配 PAT 也不迟。

`EXT_RUNTIME_TOKEN` 这个 secret 因此**没有配置，也不需要配置**。

---

## 6. 与计划文档的差异

| 项 | 计划 | 实际 | 原因 |
| --- | --- | --- | --- |
| 模块 wrapper | 模块自带 wrapper + 模块级 settings | 并入根构建，删模块 wrapper | 见 §1 |
| 制品文件名 | 未固定 | 显式固定为 `ext-runtime.jar` / `ext-runtime-shared-sources.jar` | 见 §3 |
| Kotlin 模块名 | 未提及 | 三处显式 `moduleName` | 见 §1 |
| 校验方式 | 体积/md5 对齐 | 语义等价比对 | 改名必然改字节，md5 不可能相等 |
| Android 消费通道 | GitHub Packages（PAT） | Release 资产（免鉴权） | Gradle 用不了依赖坐标做源目录，见 §7.2 |
| Android 共享源码落点 | 未定 | `android/build/ext-runtime-src`（下载产物，不进版本库） | 见 §7.2 |

---

## 7. P4：Suwayomi-next 侧怎么改

### 7.1 关键观察：所有桌面 target 其实是同一个 platform

`build.yml` 的矩阵包含 `windows-x64` / `windows-arm64` / `linux-x64` / `linux-arm64` /
`macos-x64` / `macos-arm64`。但 ext-runtime 是**纯 JVM 字节码**，`jvmToolchain(25)` +
`JavaVersion.VERSION_21` 的产物不区分平台 —— 六个 target 构建出的是同一个 jar。

原方案是每个 target 各自 `./gradlew jar`（所以才有 T10「首个 target 要下 52MB AOSP 包」）。
改成 package 通道后还能更进一步：**在 prep 里解析一次制品 URL，六个 target 复用**。

这与 WebUI 的处理方式完全同构 —— prep 用 `scripts/resolve-webui.sh "$KIND"` 解析一次，
把 `webui_url` 作为 input 传给 `build.yml`，所有 target 下载同一份。
ext-runtime 照抄这个模式：`scripts/resolve-ext-runtime.sh`，输出 `ext_runtime_url` +
`ext_runtime_version`。

### 7.2 通道选择：Release 资产，不是 Packages

两个通道都能用，但用途不同：

| 通道 | 鉴权 | 适合 |
| --- | --- | --- |
| GitHub Packages (Maven) | 跨仓库必须 **PAT (classic) + `read:packages`** | Gradle/Android 侧按依赖坐标解析 |
| **GitHub Release 资产** | **免鉴权** | 桌面 target 直接 `curl` 一个 jar |

`build.yml` 只是把 jar 拷进 `bin/`，并不需要 Maven 坐标。走 Release 资产可以：
免掉一个 PAT secret、免掉 PAT 过期导致 401 的风险（T4 的一半）、不需要 `unzip -l` 之外的校验之外的东西。

**Android 侧后来也走了 Release 资产**（与计划不同，见 §6）：原计划是「Android 走 Packages，
因为它要按坐标解析 `shared-sources` 分类器」。改主意的理由是 ——
**Gradle 没法把一个依赖当源目录用**：`:extension-host` 需要的是 `addStaticSourceDirectory`，
最终一定要下载 + 展开成目录，那 Maven 坐标带来的解析能力一点也用不上，
却要为此付出一个 PAT。既然 `-shared-sources.jar` 也挂在 Release 资产上，直接下载更省事。

代价是 Android 侧自己多了一层脚本（`android/scripts/fetch-ext-runtime-src.sh`：
下载 → 用 `scripts/unzip_any.py` 展开 → 校验三个包根 + `.kt` 数量），
以及 `:extension-host/build.gradle.kts` 在**配置期**就断言目录存在且完整 ——
不然「下载/展开出问题」会表现为编译期的「找不到符号」，指错方向。

`resolve-ext-runtime.sh` 因此照抄 `resolve-webui.sh` 的三级探测（`gh api` → 匿名 REST →
匿名 HTML），并加一个 `--sources` 开关切换资产类型（默认取桌面 jar）。判据必须**双向**：
桌面 jar 要 `不以 -shared-sources.jar 结尾`，源码包要 `以它结尾` —— 只做单向排除会让
`--sources` 也挑到桌面 jar（两者同名前缀，只差后缀）。

### 7.3 版本一致性：prep 解析一次，两个仓库对齐

`prep` 从 Release 资产名里反解版本号（`ext-runtime-<V>.jar` → `<V>`），
作为 input 传给 `build.yml`，写进编译期环境变量。

这样「本仓的版本」和「主仓库构建用的版本」在同一次发布里是同一个字符串，
排查时可以直接对上号。约定见 §8。

### 7.4 本仓库里被删掉的东西

`jvm-sandbox/`（343 个跟踪文件）与 `extension-runtime/`（52 个）已 `git rm`。
删除前逐文件核对过：

- `jvm-sandbox/**` → `ext-runtime/**`，**只有 5 个文件没有迁过去**，且都是有意丢弃的：
  `gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`、`gradlew`、`gradlew.bat`、
  嵌套的 `settings.gradle.kts`（新仓库用根 wrapper，见 §1）。
- `extension-runtime/src/main/kotlin/**` → `ext-runtime/src/shared/kotlin/**`，**52 = 52 逐字对齐**。
- 其中 `eu/kanade/tachiyomi/LICENSE`（Mihon 的 Apache-2.0，Copyright 2015 Javier Tomás）
  随之迁出并保留在新仓库 —— 本仓库因此不需要再单独留一份，`android-compat/LICENSE`
  也已按约定丢弃。

---

## 8. 版本与发布约定

### 版本号 = `<AOSP API level>.<本仓库主版本>.<修订>`

大版本**跟着 `android-stub` 的公开 API 基线走**，与 android-stub 自己的 `30.r03.1`
是同一套思路（那个是 `<api>.<包修订>.<剥离修订>`）：看一眼版本号就知道它对应哪个 Android API。

| 版本 | 含义 |
| --- | --- |
| `30.1.0` | 对应 AOSP API 30（`platform-30_r03`），ext-runtime 自己的第 1 版 |
| `30.2.0` | 还是 API 30，ext-runtime 的第 2 版 |
| `31.0.0` | AOSP 基线升到 API 31 —— 大版本跟着走，后面的计数归零重来 |

**`publish.yml` 强制这两者一致**：它从 `ext-runtime/android-stub/android-stub.properties`
读 `aospApiLevel`，与 tag 的大版本比对，不一致直接红。换 pin 忘改版本号不会静默发错版。

> 注意别把后两位当成 AOSP 包修订（`r03` 那种）。包修订只在换基线时动，而 ext-runtime
> 自己的代码每次发版都要有新版本号 —— 所以后两位是本仓库的发布计数，不是 AOSP 的。

本仓推 `v<V>` tag → `publish.yml` 发 `<V>` 到 Packages + Release 资产。

Suwayomi-next 每次构建**动态解析最新** ext-runtime 版本（与它对待 WebUI 的方式一致），
所以升级 ext-runtime **不需要改 Suwayomi-next 的任何文件**：

```
改 ext-runtime 源码 → 在本仓打 v30.2.0 tag → Suwayomi-next 下次构建自动用上
```

需要显式 pin 时（比如要复现某个旧版本），在 `build.yml` 的 `ext_runtime_url` input 里
指定具体 URL，或在 prep 里改成按 tag 取。

---

## 9. 验收结果（2026-09-24）

| # | 项 | 结果 | 证据 |
| --- | --- | --- | --- |
| V1 | 本仓独立构建 | ✅ | `./gradlew clean build` 绿；两个制品齐 |
| V2 | AOSP 溯源 | ✅ | `META-INF/android-stub.properties` 逐字段与 P0 基线一致（`version=30.r03.1`） |
| V3 | 桩去重 | ✅ | 16416 个 class，零重名；三个 `moduleName` 都已钉死 |
| V4 | 共享源码制品 | ✅ | 51 个 `.kt`，含 `eu/kanade/tachiyomi/source/Source.kt`，**不含** `sandbox/Main.kt` |
| V5 | 沙盒脱离服务端跑 | ✅ | `/health` → `{"ok":true,"extensions":192,"sources":1183}` |
| V6 | 真实扩展执行 | ⚠️ 部分 | 列表 / 章节 / 章节图片 / 筛选页都真通（真实网络）；`getMangaDetails` 在 **MangaDex** 上报 `no suspend method getMangaDetails(1+1 args)`，但**基线 jar 报一模一样的错** → 既有问题，非本次引入 |
| V7 | 桌面端到端 | ⏭️ | 未跑（需要部署整套发布布局；本次以 V5/V6 覆盖沙盒侧） |
| V8 | Docker | ⏭️ | 本机无 Docker，未跑 |
| V9 | Android | ⏳ | 需先发布 v0.1.0（脚本按 Release 资产解析） |
| V10 | 静默失败已修 | ✅ | 故意设 `SUWAYOMI_SANDBOX_JAR=/不存在`，两条 warn 都出现：`…指向的扩展沙盒 jar 不存在，继续按发布布局查找` + `未找到扩展沙盒 jar（ext-runtime.jar），扩展功能不可用` |
| V11 | Rust 侧 | ✅ | `cargo build --release -p suwayomi-server` 过；`cargo clippy --workspace --all-targets` **零告警** |
| V12 | 发布链路 | ⏳ | 需先打 tag |

### V1 的强证据：改名只动了三个文件名

拿 P0 基线 jar（`Suwayomi-builds/Suwayomi-latest/bin/jvm-sandbox.jar`，45382030 B）与
新产物（45381739 B）逐条目比：

```
条目数 旧/新: 25036 25036
仅旧有: META-INF/suwayomi-jvm-sandbox.kotlin_module
        META-INF/suwayomi-jvm-sandbox_android-compat.kotlin_module
        META-INF/suwayomi-jvm-sandbox.android-compat_config.kotlin_module
仅新有: META-INF/suwayomi-ext-runtime.kotlin_module
        META-INF/suwayomi-ext-runtime_android-compat.kotlin_module
        META-INF/suwayomi-ext-runtime_android-compat_config.kotlin_module
内容大小不同的条目数: 0
```

**25036 个条目里只有 3 个改了名字，没有任何条目内容变化。** jar 文件本身小 291 字节，
是这三个名字变短后 zip 的 deflate / 中央目录字节跟着变 —— 不是内容差异。
再叠加 `.workbuddy-ai/baseline/verify-semantic.py` 的 `javap` 语义比对（0 处差异），
「改名不改行为」这件事是**逐条目 + 逐方法体**双重确认的。

### V6 的对照实验

同一个 MangaDex 详情请求，在两个 jar 上各打 4 次：

| jar | 结果 |
| --- | --- |
| 新 `ext-runtime.jar` | 4/4 `no suspend method getMangaDetails(1+1 args)` |
| 基线 `jvm-sandbox.jar` | 4/4 **同一句错误** |

所以这是该扩展自身与反射驱动层的既有不兼容（`1+1 args` 说明它用的是带 context
parameter 的签名，反射按普通参数表找不到），与剥离无关，本次不修 ——
按「不借机重写任何沙盒逻辑」的约定，留给后续单独处理。

### Suwayomi-next 侧的验证

- `.workbuddy/verify/ci_equiv.py`：**35/35**（prep / webui / pack / publish 逐条对齐 + 接线检查）。
- `.workbuddy/verify/ci_pack_check.py`：**357/357**。
- 两个脚本的基线都从「合并前那版」改成了 **HEAD（改动前）**：4d8a5b4 之后 runner 迁移
  改了 targets 矩阵、发布说明被精简过，拿合并前那版对齐只会报一堆与本次无关的差异。
  pack 一侧比的是「改动前 build.yml ↔ 改动后 build.yml」，并把 `jvm-sandbox/` 中间产物
  与 `bin/jvm-sandbox.jar → bin/ext-runtime.jar` 这两处**预期差异**排除在比对之外。

---

## 10. JRE 裁剪为什么也搬到本仓库（2026-09-24）

`make-jre.sh` 与它的两个验证脚本（`check_jre_arch.sh`、`e2e_host_jmods.sh`）原本在
Suwayomi-next，现在都在本仓：脚本在 `scripts/`，验证脚本在 `.workbuddy-ai/verify/`。

### 理由

1. **模块白名单由沙盒需求决定，必须同仓演进。** 这份 JRE 存在的唯一目的是跑
   `ext-runtime.jar`（服务端是 Rust 二进制、托盘是原生可执行文件，都不需要 JVM）。
   白名单里的 `jdk.httpserver` 是沙盒自己的 HTTP 宿主、`java.prefs` 是共享源码里
   `PersistentCookieStore` 用的、`--include-locales=en,ja,zh` 是为扩展站的日/中文站点。
   留在一个改沙盒时不会碰的仓库里，风险是：加了个模块没人想起改白名单 → 运行期
   `NoClassDefFoundError`，而且**只在 `+jre` 包上出现**（开发机跑的是完整 JDK）。
2. **jlink 不能跨平台编译。** 产出的 `bin/java` 与原生库取自宿主 JDK，不是
   `--module-path` 里的 jmods。所以每个 `(os, arch)` 都需要一个原生 runner —— 这件事
   本仓做正合适，因为 `publish.yml` 本来就按平台铺矩阵；反过来，留在 Suwayomi-next 就
   意味着那边的主构建矩阵被一个与它无关的约束（runner 必须与目标同架构）绑架。
3. **消费侧更简单。** 资产是**按版本 + 平台**命名的，Suwayomi-next 只按
   `<V>-<os>-<arch>` 下载解开，不需要装 JDK、不需要 jmods 的下载兜底逻辑、也不会
   出现"这个版本到底有没有 JRE 资产"的判断。

### 发布形态

`publish.yml` 的 `jre` job（`needs: publish`，六格矩阵）在 `publish` 建好的那个 Release
里挂六份资产：

```
ext-runtime-jre-<V>-windows-x64.tar.gz      解压后顶层就是 jre/
ext-runtime-jre-<V>-windows-aarch64.tar.gz
ext-runtime-jre-<V>-linux-x64.tar.gz
ext-runtime-jre-<V>-linux-aarch64.tar.gz
ext-runtime-jre-<V>-mac-x64.tar.gz
ext-runtime-jre-<V>-mac-aarch64.tar.gz
```

- 每次发版**出齐六份**（`fail-fast: false`，一格挂掉不拖累其余）。代价是 `jmods`
  要按平台各下一份（约 85 MB，JEP 493 之后 Temurin JDK 归档里不再带 `jmods/`）；
  换来的是消费侧不必判断资产是否存在。脚本会先探 `$JAVA_HOME/jmods/`，有就直接用。
- **只有 `windows/aarch64` 那一格用 Azul Zulu**：Adoptium 对该平台**没有发布 JDK 25 的
  任何制品**（`jdk`/`jre`/`jmods` 三端点全 404，该平台在 Adoptium 上最高只到 JDK 21），
  拿不到 jmods 就出不了 +jre。Zulu 的 `win_aarch64` 归档自带 `jmods/`。
- 消费侧的 `base=` 输出让拼 URL 变简单：`scripts/resolve-ext-runtime.sh` 吐
  `url=` / `version=` / `base=`，其余资产按 `<base>/<资产名>` 拼即可，不必为每种
  `(os, arch)` 再探测一遍。

### 搬过来的三个坑（原来记在 Suwayomi-next 的 `docs/release.md`）

- **`uname -m` 在 `windows-11-arm` 上会撒谎**：镜像确实是原生 arm64、装的也是货真价实的
  `win_aarch64` JDK，但 runner 上的 **Git for Windows 是 x64 版**，MSYS 的 `uname -m`
  因此报 `x86_64`。「宿主架构必须等于目标架构」这道闸因此误判过一次（run 35074440661，
  jlink 都没来得及启动）。现在宿主架构**优先读 `$JAVA_HOME/bin/java` 的可执行文件头**
  （与产物自检同一套偏移表），再退到 `release` 的 `OS_ARCH`，最后才是 `uname -m`。
- **两道闸**：入口比「宿主平台 vs 目标平台」，末尾核「产物 magic **+ 架构**」。只判
  magic 拦不住同格式但错架构的产物（x64 的 jlink + aarch64 的 jmods 就会产出那种），
  装上就是 `UnsatisfiedLinkError`。自检代码在 `make-jre.sh` 的 `binary-probe` 标记块里，
  被 `check_jre_arch.sh` 整块抽出来单测（合成夹具 + CI 真产物夹具，共 37 项）。
- **`JAVA_HOME` 是 Windows 形式时不能直接做路径名展开**：`setup-java` 注入的是
  `C:\hostedtoolcache\…`，反斜杠在 bash 的 glob 里是转义符 —— `[[ -d "$JAVA_HOME/jmods" ]]`
  认得，`compgen -G "$JAVA_HOME/jmods/*.jmod"` 却永远匹配不到。`jmods_dir()` 因此先把
  `JAVA_HOME` 过 `cygpath -u` 归一化、并用 `find` 代替 glob。这个坑**本地复现不了**
  （本机 Temurin 25 按 JEP 493 不带 jmods，两条分支都走下载），只在 windows-arm64 上炸
  （run 35078586922）。

### Suwayomi-next 侧因此删掉的东西

- `build.yml` 的「安装 JDK」步骤**整步删除**；`release.yml` 矩阵里的 `jdk` 列删除
  （它只服务那一步）；`+jre` 分支改成 `curl` + `tar -xzf` + `java -version` 冒烟。
- `Dockerfile` 的第 3 段从「`eclipse-temurin:25-jdk` 里跑 jlink」改成
  「`alpine` + 下载 `ext-runtime-jre-<V>-linux-<arch>.tar.gz`」，镜像里不再有 JDK。
- `scripts/make-jre.sh` 删除；`.workbuddy/verify/{check_jre_arch.sh,e2e_host_jmods.sh}`
  删除（现居本仓）。验证脚本相应改桩：`ci_equiv.py` 造两份 JRE 夹具
  （`-win` 那份启动器叫 `java.exe`，与真资产一致），`ci_pack_check.py` 的 curl 桩按 URL
  里的 os 段现场 tar 一份出来。改完两边仍全绿：**35/35** 与 **358/358**。

