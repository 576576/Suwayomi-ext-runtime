# 让最终 jar 不打包无用库（2026-09-30 调研）

> 现状 `ext-runtime.jar` = **45.91 MiB**。其中与扩展**完全无关**的 AOSP 资源占 **19.70 MiB（43%）**。
> 三档排除合计可到 **21.50 MiB（−53%）**；P1 走类清单则到 **19.58 MiB（−57%）**。
> **落地情况：尚未实施**，等确认。本文的每个数字都是实测，不是估算。

## 结论先行

| 档 | 内容 | 省（压缩后） | 兼容风险 | 前置条件 |
|---|---|---|---|---|
| **P0** | 桩里的 `res/` + `resources.arsc` + `assets/` + `AndroidManifest.xml` | **19.68 MiB** | **无** —— 仓库里没有任何代码读它们（证据见下） | 无 |
| **P1** | 桩里没人链接的类（实测 4,246/4,503 = 94.3%） | **1.08 MiB**（整包前缀）／**3.00 MiB**（类清单） | 收窄链接面：第三方扩展若链了被排除的类，加载期 `NoClassDefFoundError` | 台架跑闸门 |
| **P2** | 应用侧无人引用的依赖（RxJava 2 / Gson / Moshi / okhttp-zstd） | **3.65 MiB** | 收窄扩展可用库面（但 Mihon 的扩展面也没有这几个） | 台架跑闸门 |

P0 是纯赚，建议先做；P1/P2 是「拿兼容面换体积」，要不要做取决于是否愿意接受「非 keiyoushi 扩展可能链到被砍的东西」。

## 一、体积账（实测）

`ext-runtime.jar`：24,873 条目，未压缩 90.44 MiB，条目压缩合计 41.47 MiB，**文件 45.91 MiB**。

| 内容 | 条目 | 未压缩 | 压缩后 |
|---|---|---|---|
| `res/`（AOSP 框架资源：壁纸、锁屏图、图标…） | 8,339 | 16.42 MiB | **14.90 MiB** |
| `resources.arsc`（AOSP 框架资源表） | 1 | 17.38 MiB | **4.50 MiB** |
| `jni/`（okhttp-zstd 的 6 个原生库：3 OS × 2 arch） | 6 | 3.52 MiB | 1.37 MiB |
| `assets/`（AOSP 框架自带图片） | 20 | 0.22 MiB | 0.22 MiB |
| `AndroidManifest.xml`（AOSP 框架清单） | 1 | 0.37 MiB | 0.06 MiB |
| `NOTICES/`（libcore 署名） | 1 | 0.45 MiB | 0.02 MiB |
| `.class` 合计 | 16,399 | 51.65 MiB | 20.28 MiB |

class 里的大头（压缩后）：AOSP 桩 3.26 MiB（4,503 类）、`kotlin/reflect` 2.65、`io/reactivex`（RxJava 2）1.89、
`kotlinx/coroutines` 1.34、`org/mozilla`（Rhino，quickjs 的实现）1.12、`com/googlecode`（dex2jar）0.99、
`rx`（RxJava 1）0.93、`okhttp3` 0.86、`kotlinx/serialization` 0.69。

**注意 `kotlin/reflect` 那 2.65 MiB 不能砍**：1,380 个扩展里有 69 个直接链它（`config4k` 与
`kotlinx.serialization` 的反射路径也依赖）。同理 `okhttp3` 虽然 0 个扩展直接链（扩展走共享源码树的
`NetworkHelper`），但应用侧在用。

## 二、P0：桩里的非 class 内容 —— 19.68 MiB，零兼容风险

`android-stub` 是按源码路径剥离 AOSP `android.jar` 得到的，而 AOSP 的 `android.jar` 里
**84% 的体积不是 class**：它带整套框架资源（`resources.arsc` 17.38 MiB + `res/` 8,339 个文件）。
桌面沙盒永远用不到它们 —— 我们用两条独立证据确认：

1. **字符串扫描**：fat jar 里含 `resources.arsc` 字面量的只有 3 个类 —— `sandbox/ExtensionLoaderKt`
   （它是在**过滤扩展 APK** 的条目）与 `net.dongliu.apk.parser.*`（apk-parser 读**扩展 APK** 的
   资源表拿图标与名字）。没有一个是读桩自己那份的。
2. **源码扫描**：全仓对 `res/` 的引用只有 `ExtensionLoader` 的 `isJarResource()` 与它的单测，
   语义都是「扩展 APK 的 res 不搬」。`RCompat` 是纯内存实现（`xyz/nulldev/androidcompat/res/`），
   不读任何二进制资源表。

fat jar 根部那两个 `r_values.ini` / `r_styles.ini`（各 0.03 MiB）**不是**从 `resources.arsc` 生成的
—— 它们来自 `net.dongliu:apk-parser` 自带的 `ResourceLoader` 资源，砍掉桩的 `resources.arsc` 不影响它们。

`NOTICES/` 建议**保留**（0.02 MiB）：里面是 libcore 的署名，我们确实在分发 AOSP 的类。

**做法**（`ext-runtime/android-stub/build.gradle.kts` 的 `tasks.jar`，与已有的 `coreLibPrefixes`
排除并列）：

```kotlin
// AOSP 的 android.jar 里 84% 是框架资源（resources.arsc + res/），桌面沙盒不读它们：
// 全仓只有 ExtensionLoader 在过滤「扩展 APK」的 res，apk-parser 读的是扩展 APK 的资源表。
exclude("res/**", "resources.arsc", "assets/**", "AndroidManifest.xml")
```

**连带要改的**：`stripRevision` 1 → 2（产物内容变了，见 pin 文件里那条约定），并在
`writeProvenance` 里加一行 `stub.exclude.resources=true` 之类，让「这个 jar 是什么构成」随产物走。

## 三、P1：没人链接的桩类 —— 最多 3.00 MiB

### 怎么算出来的

`scripts/ext-linkage-scan.py` 做三件事，缺一不可：

1. **扩展侧**：读每个扩展 APK 里 `classes*.dex` 的 `type_ids`（DEX 里所有类型引用都经这张表）。
   本次扫的是 **1,380 个 keiyoushi 扩展**（当前全量，缓存见下），得到 946 个被引用的类型，
   其中**落在桩里的只有 57 个**。
2. **应用侧**：fat jar 里不属于桩的类（`android-compat` / `sandbox` / `eu.kanade` …）也引用桩。
   扫它们得到 205 个。
3. **继承闭包**：JVM 在**加载**类时就要解析父类与接口（不像方法体那样惰性解析）。把可达集沿
   `super`/`interfaces` 边扩张到不动点，补进 17 个 —— 所以**必须留 257 个类**。

结论：**可排除 4,246 / 4,503 类（94.3%），未压缩 8.06 MiB，压缩后 3.00 MiB。**

### 但「整包排除」只吃到三分之一

整包一个类都不需要的只有 **37 个包**（`android/media` 不是，它要留 1 个）：

```
android, android/accessibilityservice, android/accounts, android/adservices, android/annotation,
android/appwidget, android/bluetooth, android/companion, android/credentials, android/crypto,
android/devicelock, android/drm, android/gesture, android/hardware, android/health,
android/inputmethodservice, android/location, android/mtp, android/nfc, android/opengl,
android/preference, android/printservice, android/ranging, android/renderscript, android/sax,
android/se, android/security, android/service, android/speech, android/telecom, android/telephony,
android/transition, android/window, androidx/annotation, dalvik/annotation, dalvik/bytecode,
dalvik/system
```

37 个包 = 1,513 类 / **1.08 MiB 压缩**，只占 P1 收益的 36%。剩下 64% 在 17 个**只能逐类砍**的大包里：

| 包 | 可排除类数 | | 包 | 可排除类数 |
|---|---|---|---|---|
| `android/media` | 464（留 1） | | `android/os` | 120（留 13） |
| `android/app` | 359（留 8） | | `android/content` | 114（留 35） |
| `android/net` | 326（留 10） | | `android/text` | 114（留 30） |
| `android/view` | 313（留 62） | | `android/util` | 42（留 20） |
| `android/provider` | 238（留 1） | | `android/animation` | 27（留 2） |
| `android/icu` | 207（留 7） | | `android/print` | 21（留 1） |
| `android/widget` | 184（留 6） | | `android/webkit` | 16（留 23） |
| `android/graphics` | 142（留 29） | | `android/system` | 12（留 2） |
| | | | `android/database` | 9（留 7） |

**建议**：先只做整包那 37 个（1.08 MiB，37 行前缀，一眼能审），把类清单版留成
`-PstubSlim=full` 的可选档。为了 1.92 MiB 额外收益去维护一份 4,246 行的清单，性价比不高。

### 机制：让构建自己复核闭包

类清单必须是**构建期可复核**的，否则一份过期的清单会静默变成运行时 `NoClassDefFoundError`。
建议在 `android-stub/build.gradle.kts` 里加一个校验：读 `slim-exclusions.txt`（或前缀表）后，
扫桩里**被保留**的类的 `super`/`interfaces`，若有指向被排除类的就 fail。这条检查把「清单过期」
从运行时故障降级成构建失败。

## 四、P2：没人引用的依赖 —— 3.65 MiB

| 依赖 | fat jar 里 | 压缩后 | 扩展引用 | 应用引用 |
|---|---|---|---|---|
| `io.reactivex.rxjava2:rxjava:2.2.21` | `io/reactivex/` 1,659 类 | **1.89 MiB** | **0 / 1,380** | **0 处** |
| `com.squareup.okhttp3:okhttp-zstd:5.5.0` | `jni/` 6 个原生库 | **1.37 MiB** | 扩展不直接引 okhttp | 共享源码树在用 |
| `com.google.code.gson:gson:2.11.0` | `com/google/gson/` 223 类 | 0.24 MiB | **0 / 1,380** | 0 处 |
| `com.squareup.moshi:moshi` + `moshi-kotlin` | `com/squareup/moshi/` 99 类 | 0.15 MiB | **0 / 1,380** | 0 处 |

RxJava 2 是纯死重：`io/reactivex/**` 在 fat jar 里**包外引用者 0**，扩展侧 0。
真正被用的是 RxJava **1**（`io.reactivex:rxjava:1.3.8`，包名 `rx.**`，27 个扩展 + 共享源码树的
`OkHttpExtensions.asObservable`），**别一起删了**。

这三个「0 引用」不是巧合 —— 看 Mihon 自己的扩展面（`gradle/libs.versions.toml` + `source-api`）：
暴露给扩展的只有 `jsoup` / `injekt` / `kotlin-reflect` / `kotlinx-serialization(-json/-jsonOkio/-protobuf)`
/ `quickjs` / `rxJava`（**1.x**）。**没有 gson、没有 moshi、没有 RxJava 2。** 砍掉它们是向参考实现
的契约靠拢，不是偏离。

`okhttp-zstd` 是另一回事：它只在站点返回 `Content-Encoding: zstd` 时才起作用，去掉后 okhttp
不再声明支持 zstd，站点会退回 gzip。省 1.37 MiB 的代价是「万一某个站点只给 zstd」—— 风险低但非零，
单独决策。

## 五、落地顺序与机制汇总

| 步骤 | 改哪里 | 复核手段 |
|---|---|---|
| P0 | `ext-runtime/android-stub/build.gradle.kts` 的 `tasks.jar` 加 4 条 `exclude`；`stripRevision` +1；provenance 记构成 | `./gradlew build` 绿 + `unzip -l` 看 `res/` 消失 |
| P1（保守） | 同上，加 37 个包前缀的 `exclude` | 构建期闭包校验 + 台架闸门 |
| P1（完整，可选） | 提交 `ext-runtime/android-stub/slim-exclusions.txt`，构建读它 | 同上 |
| P2 | `ext-runtime/build.gradle.kts` 删 4 条 `implementation(...)` | `./gradlew build` 绿 + 台架闸门 |

`ext-runtime/android-stub/slim-exclusions.txt` 由 `scripts/ext-linkage-scan.py --out` 的
`slim-analysis.json` 的 `excludable` 字段生成（一行一个内部类名，`#` 开头为注释）。

## 六、验证闸门（必须跑，不能只看编译过）

编译通过只能说明本仓没断，**不能**说明扩展还链得上。按 `docs/agent/sandbox.md` 的台架：

```bash
# 台架：隔离根 E:\Github\Suwayomi-builds\ext-lab（沙盒 4599），192 个 APK
python ext_survey.py --root E:/Github/Suwayomi-builds/ext-lab \
    --sandbox http://127.0.0.1:4599 --langs zh,zh-Hans,zh-Hant,all --tag slim
python ext_consistency.py --sandbox http://127.0.0.1:4599 --concurrent 3 --rounds 2
```

**改造前基线（2026-09-30 实测，就是这个台架配当前 jar）**：

```
/health          -> {"ok":true,"extensions":192,"sources":1168}
/reload          -> extensions=192 sources=1168 failures=0
ext_consistency  -> 2/2 轮自洽（3 并发 × 2 轮，每轮都是 192 / 1168 / 0 失败）
```

验收口径：`loaded` = 192/192、`/sources` = 1,168、`failures` 为空、`ext_consistency` 自洽。
台架这次是用 keiyoushi 当前索引重建的（种子库里的 `apk_url` 指向的 release tag 已被上游删掉，
已按当前索引回填），所以源总数与历史记录里的 1,183 不同 —— **以本节的 1,168 为基线**，别拿旧数字比。

扩展链接面的原始证据（1,380 个 APK 的类型并集）在
`E:/Github/Suwayomi-builds/ext-lab-cache/`（`apk/`、`slim-analysis.json`、`linkage-1380.json`）。

## 七、风险与回滚

- **P1 的残留风险**是「非 keiyoushi 扩展链到被排除的类」。实测面覆盖 1,380 个 keiyoushi 扩展，
  覆盖不了自建/第三方仓库。整包版的 37 个包都是语义上「漫画阅读器不可能用到」的子系统
  （电话、蓝牙、健康、打印、NFC、无障碍、输入法、凭证…），残留风险集中在逐类版。
- **P1 的维护成本**：换 API 基线或大批新扩展后清单会过期。构建期闭包校验能挡住「父类被砍」这一类，
  挡不住「新扩展链了被砍的类」—— 后者只能靠闸门。
- **P2 的残留风险**：非 keiyoushi 扩展用了 gson/moshi/RxJava 2。若收到这类反馈，把对应依赖加回来即可。
- **回滚**：改动都在两个 `build.gradle.kts` 加一个可选清单文件里，`git revert` 即可；产物全是
  `build/` 下的构建产物，工作树没有别的东西要还原。记得把 `stripRevision` 一并回退。
