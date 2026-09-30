# Java → Kotlin 迁移 / Rust 侧抽离 调研（2026-09-30）

## 结论先行

仓库里**只有一处 Java**：`ext-runtime/android-compat/src/main/java`，
**删完死代码后**是 274 个文件 88,239 行（删之前 280 / 89,740）。
其中 96% 是 vendored 的 AOSP / support 库源码与桩，**不该迁**；本仓自己写的 Java
**删完只剩 4 个文件 1,680 行**（删之前 9 / 3,142：1,081 行是死代码、385 行无人引用）。

所以「把 Java 迁到 Kotlin」这件事本身的收益面非常小：真正值得迁的只有 **105 行**
（`BuildConfigCompat.java` + `RCompat.java`）。真正的收益在另外两件事：

1. **删掉 1,495 行死代码** —— 2026-09-30 已删，见 §五；
2. **把沙盒侧的 config 子系统抽给 Rust** —— 2026-09-30 已做，见
   [`RUST_HANDOFF.md`](RUST_HANDOFF.md)（顺带去掉 `appdirs` 依赖）。

配套：[`REF_IMPL_DIFF.md`](REF_IMPL_DIFF.md) —— 与参考实现（**Suwayomi-Server** 的
`AndroidCompat/`，以及 **Mihon** 的扩展 API）的差异对照，外加 `org/json` / `quickjs`
有没有 Kotlin 实现可换。**本仓不与参考实现同步**，两者只是对照与溯源关系。

## 一、盘点

按归属分类（`android-compat/src/main/java` 下全部 `.java`）：

| 类别 | 文件 | 行数 | 判定 |
| --- | --- | --- | --- |
| `android/**` | 239 | 78,753 | **不迁**（AOSP 源码搬运 + 桩，签名即契约） |
| `com.android.internal/**` | 5 | 3,058 | **不迁**（AOSP 内部实现搬运） |
| `org/json/**` | 7 | 2,713 | **不迁**（第三方 vendored，可考虑换 maven 坐标） |
| `xyz.nulldev.androidcompat/**` | 4 | 1,680 | 本仓自研，逐文件见下（已删 5 个） |
| `androidx/**`（含 `androidx.preference`） | 9 | 533 | **不迁**（support 库桩） |
| `libcore/**` | 3 | 693 | **不迁** |
| `dalvik/**` | 3 | 483 | **不迁** |
| `app.cash.quickjs/**` | 2 | 190 | **不迁**（签名被扩展 dex 直接链接） |
| `com.squareup.duktape/**` | 2 | 136 | **不迁**（`DuktapeStub.java` 已删） |
| `android/annotation` + `android/support/annotation` | 91 | 3,612 | **不迁不删**（注解桩，扩展 dex 会带注解引用） |

本仓自研的 9 个 Java 文件逐一看：

| 文件 | 行数 | 判定 | 理由 |
| --- | --- | --- | --- |
| `androidimpl/FakePackageManager.java` | 850 | **不迁** | `android.content.pm.PackageManager` 的子类，签名与 AOSP 强绑定 |
| `androidimpl/CustomContext.java` | 721 | **不迁** | `android.content.Context` 的子类，同上 |
| `io/sharedprefs/JsonSharedPreferences.java` | 385 | **已删** | 全仓 grep 无任何引用；真正被 `CustomContext` 用的是 Kotlin 版 `JavaSharedPreferences.kt` |
| `replace/java/util/TimeZone.java` | 195 | **已删** | 死代码（见下） |
| `replace/java/text/NumberFormat.java` | 248 | **已删** | 死代码 |
| `replace/java/util/Calendar.java` | 293 | **已删** | 死代码 |
| `replace/java/text/SimpleDateFormat.java` | 345 | **已删** | 死代码 |
| `res/RCompat.java` | 78 | **可迁 Kotlin** | 自研小工具类，无 AOSP 契约 |
| `res/BuildConfigCompat.java` | 27 | **可迁 Kotlin** | 同上 |

`replace/java/**` 为什么是死代码：这四个类的包名是
`xyz.nulldev.androidcompat.replace.java.util` / `…java.text`，**不是** `java.util` / `java.text`，
所以它们根本不可能「替换」JDK 的类；而 `android-compat/build.gradle.kts:33` 的注释也写着
「无任何外部引用」。全仓 grep 一致。

## 二、为什么 `android/**` 那 78k 行不能迁

1. **签名就是契约**。扩展编译产物（dex）按 AOSP 的签名链到 `android.content.Intent`、
   `android.net.Uri`、`android.os.Bundle` 这些类上，方法签名、可见性、静态嵌套结构
   都必须与 AOSP 逐一对齐。Kotlin 能近似表达，但 `package-private` 字段、
   `protected` 成员、数组协变、静态嵌套类的语义都会漂移。
2. **改动面不可控、可追溯性变差**。这些文件来自参考实现
   [Suwayomi-Server 的 `AndroidCompat/`](https://github.com/Suwayomi/Suwayomi-Server)，
   本仓**零自建文件**——逐文件 sha256 比对：300 个文件里 **288 个与参考实现逐字节一致**
   （见 `REF_IMPL_DIFF.md`）。整体 Kotlin 化之后，「哪些偏差是本仓有意改的、哪些是翻译过程
   引入的」会混在一起，将来回溯某个行为差异得先分辨这个。
   注意：本仓**不与参考实现同步**，所以这不是"同步成本"问题，而是**可追溯性**问题——
   它比同步成本弱，但仍然存在。
3. **Kotlin 会改异常类型**。编译器给 public 方法的非空参数插 `Intrinsics.checkNotNullParameter`，
   抛的是 `NullPointerException` / `IllegalArgumentException`，而 Java 原实现可能是静默容忍或
   抛别的异常。扩展 `catch` 的异常类型一旦漂移，表现就是「加载率没变、可用率掉了」——
   这种回归在 `ext_survey.py` 上才看得出来，编译期和单测都发现不了。
4. **收益为零**。这些类是给扩展链接用的，不是本仓的业务代码，Kotlin 的空安全/简洁
   对它们没有价值。

## 三、可以抽离给 Rust 侧实现的

判断原则只有一条：**扩展会不会同步调用它**。扩展代码跑在沙盒 JVM 里，凡是扩展会直接
调用到的东西（`SharedPreferences`、`SQLite`、JS 引擎、HTTP）都不能搬到 Rust；
只有「宿主启动时的一次性配置」和「与服务端 DB 重复的元数据」能搬。

| 候选 | 规模 | 能否抽离 | 说明 |
| --- | --- | --- | --- |
| `android-compat/config/**`（`ConfigManager` 等 6 个 Kotlin 文件）+ `resources/server-reference.conf` | ~330 行 + 3 个依赖（`typesafe-config`、`config4k`、`appdirs`） | **能，推荐** | 配置项（`android.system.isDebuggable`、应用根目录等）完全可以由 Rust 侧算好、经环境变量/启动参数下发。目录推导已经在 `33610de` 收口到 `SUWAYOMI_APPDATA_DIR`，这条路走了一半。抽掉后 Jar 少 3 个依赖。 |
| `xyz.nulldev.androidcompat.pm.*`（`PackageController` 91 / `InstalledPackage` 114 / `PackageUtil` 29）+ `FakePackageManager` 850 | ~1,084 行 | **部分能** | 扩展包的安装清单、包名→元数据，Rust 侧 DB 里本来就有（extension 表 + APK 元数据）。沙盒侧只需要一个最小 `PackageManager` 契约。收口前先确认哪些方法被真实调用。 |
| `PersistentCookieStore` / `CookieManagerImpl` | 235 + 96 行 | **不建议** | 扩展同步访问 cookie，搬到 Rust 要跨进程同步调用，得不偿失。 |
| `android/database/sqlite/**`（34 文件）+ `ScrollableResultSet.kt` 1072 + `io/requery` | 大 | **不能** | 扩展在进程内自建 SQLite 存储。 |
| Dex → jar 转换（`DexTranslator` / `BytecodeFixer`） | 696 行 | **不能** | 依赖 dex2jar / ASM，必须 JVM 工具链。 |
| `FilePreferences` / `ExtensionPreferences` | 377 行 | **不能** | 扩展同步读写 `SharedPreferences`。但「备份用的扁平读写」已经做成 HTTP 端点 `/source/{id}/preferences/raw`，Rust 侧走那个即可，不要碰沙盒内这份。 |
| `org/json/**` | 2,713 行 | **不能抽，但可换** | 运行时真被用到（AOSP 包里 `org/json` 被当核心库剥掉了，只有这份实现）。可以考虑换成 maven 的 `org.json:json` 去掉 vendored 代码，属于「替依赖」而不是「抽离」。 |

## 四、迁移时的工程约束（动手前必读）

1. **剥离集合按源码路径推导，`.java` 与 `.kt` 一视同仁**：
   `android-stub/build.gradle.kts` 的 `dedupPatterns()` 扫三个源根（含
   `android-compat/src/main/java` 与 `src/shared/kotlin`），按相对路径算类名，
   额外剔除 `<stem>Kt.class`。所以 Java → Kotlin **不会**破坏剥离逻辑。
   但一个 Kotlin 文件里放多个顶层类时，文件名与类名不一致会多剔除一个 `XxxKt`，
   改名后要让 `verifyStubDedup` 过一遍。
2. **`android-compat` 的 Java 目标版本是 21**（major 65），Kotlin 侧 `jvmTarget = 21`，
   两边一致；测试任务被钉在 JVM 25 上跑，别动 `tasks.test` 的 launcher。
3. **迁 `android.*` 契约类要在 `ext-runtime/android-compat/README.md` 的「相对参考实现的
   改动」列表里记一笔**（那里已经记了 CEF WebView 的删除、Rhino 换 graalvm、Cleaner 迁移、
   死代码删除等）—— 本仓不与参考实现同步，这份列表的作用是对照与溯源，不是同步依据。
4. **验证不看编译、看加载率**。任何涉及 `android-compat` 的改动，判据是
   `ext_survey.py` 的加载率/可用率与 `ext_consistency.py` 的三条不变式，
   不是「build 绿了」。

## 五、建议的执行顺序

| 优先级 | 动作 | 规模 | 风险 |
| --- | --- | --- | --- |
| ~~P0~~ | 删 `replace/java/**` + `JsonSharedPreferences.java` + `DuktapeStub.java` | **−1,495 行，已完成** | 已复核：全仓 grep 无引用、`./gradlew build` 全绿、桩 jar CRC 逐字节不变（剥离集合未受影响）、fat jar 51,528,049 → 51,505,841 B（17,821 → 17,813 类）。`com.ibm.icu` 是唯一被死代码用到的依赖，`compileOnly("com.ibm.icu:icu4j:78.3")` 一并移除 |
| ~~P2~~ | config 子系统抽给 Rust（`SUWAYOMI_APPDATA_DIR` + `-D` 覆盖） | **已完成** | 见 [`RUST_HANDOFF.md`](RUST_HANDOFF.md)：根由 Rust 侧那个 env 决定，去掉 `appdirs`；三个 config 模块改为可被 `-D` 逐项覆盖。实测三种启动方式均符合预期 |
| P1 | `BuildConfigCompat.java` / `RCompat.java` 迁 Kotlin | 105 行 | 低（顺手验证剥离与 dedup 不受影响） |
| P3 | `pm/*` + `FakePackageManager` 收口 | ~1,084 行 | 中（先摸清哪些方法被真实调用） |
| 不做 | `android/**` 78,753 行整体 Kotlin 化 | — | 收益为负，见 §二 |
