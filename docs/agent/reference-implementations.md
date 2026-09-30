# 与参考实现的差异对照 + `org/json` / `quickjs` 的 Kotlin 实现调研（2026-09-30）

> 本仓**不与任何参考实现同步**。本文的用途是：① 说清本仓相对参考实现改了什么、为什么改；
> ② 记下 `org/json` 与 `quickjs` 有没有现成 Kotlin 实现可换。
>
> 参考实现有两个：**Suwayomi-Server**（`AndroidCompat/`，桌面 JVM 上的 `android.*` / `androidx.*`
> 桩与实现）与 **Mihon**（`eu.kanade.tachiyomi.**`，扩展面契约形状）。

## 一、与 Suwayomi-Server `AndroidCompat/` 的差异（逐文件 sha256 比对）

比对对象：本地 `E:\Github\Suwayomi-Server`（HEAD `d37230ee`）的
`AndroidCompat/src/main/java` 与 `AndroidCompat/Config/src/main/java`。

| | android-compat | config 模块 |
| --- | --- | --- |
| 本仓文件数 | 300 | 6 |
| 参考实现文件数 | 311 | 6 |
| **与参考实现逐字节一致** | **288（96%）** | **6（100%）** |
| 本仓改动过 | 12（24,479 行） | 0（config 抽离后会产生） |
| 本仓自建 | **0** | 0 |
| 参考实现有、本仓没有 | 11（删掉的部分） | 0 |

**本仓零自建文件**——`android-compat` 下没有一个文件是本仓凭空写的，全部来自参考实现。

### 本仓改动过的 12 个文件

改动原因都记在 `ext-runtime/android-compat/README.md`，这里只是把规模摆出来
（"为什么改"看那份清单）：

| 文件 | 行数 | 改动性质 |
| --- | --- | --- |
| `android/content/Intent.java` | 7,626 | 补 `@Deprecated` |
| `android/content/pm/PackageManager.java` | 5,319 | 补 `@Deprecated` |
| `android/net/ConnectivityManager.java` | 2,927 | 补 `@Deprecated` |
| `android/database/sqlite/SQLiteDatabase.java` | 2,265 | `finalize()` → `Cleaner` |
| `android/webkit/WebSettings.java` | 1,763 | 补 `@Deprecated` |
| `android/os/MessageQueue.java` | 1,272 | `mPtr` → `AtomicLong` + `Cleaner` |
| `xyz/…/androidimpl/FakePackageManager.java` | 851 | 随 `PackageManager` 补 `@Deprecated` |
| `xyz/…/db/ScrollableResultSet.kt` | 1,073 | 补 `@Deprecated` |
| `android/net/NetworkInfo.java` | 497 | 补 `@Deprecated` |
| `android/database/AbstractCursor.java` | 417 | 删 `finalize()` |
| `android/database/sqlite/SQLiteCursor.java` | 287 | `finalize()` → `Cleaner` |
| `app/cash/quickjs/QuickJs.java` | 182 | graalvm polyglot → Rhino（省 67 MB） |

删掉的 11 个：`AndroidCompatInitializer.kt` + `webkit/Cef*.kt` ×4，
以及 2026-09-30 删的 `replace/java/**` ×4、`JsonSharedPreferences.java`、`DuktapeStub.java`。

## 二、这对「迁不迁 Kotlin」意味着什么（口径变化，重要）

以前不迁 Kotlin 的头号理由是"会毁掉与上游的逐字节同步能力"——
**这个理由现在已经不成立**：本仓不与之同步，改多少都是本仓自己的事。

剩下的反对理由只有三条，且都是技术性的：

1. **签名就是契约**：扩展的 dex 按 AOSP 的签名链到 `Intent` / `Uri` / `Bundle` 这些类上。
   Kotlin 能近似表达，但 `package-private` 字段、`protected` 成员、数组协变、静态嵌套结构
   都会漂移。
2. **Kotlin 会改异常类型**：编译器给 public 方法的非空参数插 `Intrinsics.checkNotNullParameter`，
   抛 `NullPointerException` / `IllegalArgumentException`，而 Java 原实现可能是静默容忍或抛别的。
   扩展 `catch` 的异常类型一旦漂移，表现为「加载率没变、可用率掉了」——编译期和单测都发现不了，
   只有 `ext_survey.py` 看得出来。
3. **收益本来就小**：见 [`java-kotlin-survey.md`](plans/java-kotlin-survey.md)——真正值得迁的只有 105 行。

所以结论没变（**当前不迁**），但**理由变了**：不再是"怕毁掉同步"，而是"签名契约风险
+ 收益太小"。若将来要迁，现在可以更自由地从那 12 个已改动文件下手（它们本来就已经偏离
参考实现），不必再顾虑"污染同步"。

## 三、`org/json/**`：没有 Kotlin 实现，也不能换

- 官方只有 Java 版（`stleary/JSON-java`，自我描述就是"a JSON package **in Java**"）。
  Android 开发者站点提供 Kotlin 版 API 参考页，但那只是同一套 Java API 的 KDoc 渲染。
- **本仓这份是 AOSP 分支，不是 stleary 版**：`org/json/JSONObject.java` 头部是
  `Copyright (C) 2010 The Android Open Source Project`，并且 `import androidx.annotation.NonNull/Nullable`。
  所以「换成 maven 的 `org.json:json`」**不是等价替换**——两条线的方法集有差异，
  扩展的 dex 是按 AOSP 版链接的，换过去可能 `NoSuchMethodError`。
- `kotlinx.serialization` 的 `JsonObject` 形状完全不同（不可变、需要 `@Serializable`、
  没有 `optString` 那套宽松语义），**无法替代**；扩展链接的是 `org.json.JSONObject`
  这个具体类的具体方法，不是接口。

**结论：保持 vendored Java，不动。**

## 四、`app/cash/quickjs/QuickJs`：也没有可替换的 Kotlin 实现

- 本仓这 182 行**把参考实现的 `QuickJs` 换成 Rhino 跑**（参考实现用 graalvm polyglot，
  为它要多背 67 MB；`quickjs-android` 带 native `.so`，桌面 JVM 上没有）。
  公开签名必须保持：`create()` / `evaluate(String[,String])` /
  `compile(String,String):ByteArray` / `execute(ByteArray):Object` / `set(String,Class<T>,T)` / `close()`。
- 搜到的 `dokar3/quickjs-kt` 是 QuickJS 的 Kotlin binding，两点都不成立：
  ① 包名是 `io.github.dokar3.quickjs`，不是 `app.cash.quickjs`，扩展链接不上；
  ② 它同样走 native 绑定，桌面 JVM 上和 `quickjs-android` 一样缺 `.so`
  ——正好是本仓改用 Rhino 想绕开的那个问题。

**结论：没有可换的实现。** 想 Kotlin 化只能把本仓这 182 行的 Rhino 桥自己重写一遍；
它在"已偏离参考实现"的那 12 个文件里，阻力最小，但收益也只有 182 行，排不上优先级。
