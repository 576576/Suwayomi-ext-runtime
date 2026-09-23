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
所以 Suwayomi-next 侧只能配 **PAT (classic) + `read:packages`**，放进 secret
`EXT_RUNTIME_TOKEN`。公开包同样需要鉴权。

因为 PAT 这条路对 CI 有摩擦，发布时**同时**把两个制品挂到 GitHub Release 资产上 ——
Release 资产免鉴权，`curl -fLO` 即可，留给「只想拿 jar」的场景。

---

## 6. 与计划文档的差异

| 项 | 计划 | 实际 | 原因 |
| --- | --- | --- | --- |
| 模块 wrapper | 模块自带 wrapper + 模块级 settings | 并入根构建，删模块 wrapper | 见 §1 |
| 制品文件名 | 未固定 | 显式固定为 `ext-runtime.jar` / `ext-runtime-shared-sources.jar` | 见 §3 |
| Kotlin 模块名 | 未提及 | 三处显式 `moduleName` | 见 §1 |
| 校验方式 | 体积/md5 对齐 | 语义等价比对 | 改名必然改字节，md5 不可能相等 |
