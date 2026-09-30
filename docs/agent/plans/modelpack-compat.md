# TachiyomiX 图像增强插件（modelpack）兼容分析（2026-09-30 调研）

样本：TachiyomiX 0.1.3a 发行包里的 10 个 modelpack APK（该发行包把增强插件收在一个
「按需安装」的子目录里，宿主 APK `TachiyomiX-0.1.3a-arm64-v8a.apk` 放在同级）。

本文只回答三件事：**它们是什么**、**桌面沙盒今天拿它们怎么办**（实测，不是推断）、
**若将来要支持，差距在哪一层**。

## 结论先行

- **它们不是扩展，是模型数据包。** `hasCode=false`、无 `tachiyomi.extension.class`，
  唯一能跑代码的 dex 里只有一个 `Lcom/tachiyomix/modelpack/R`。全部消费逻辑在 TachiyomiX
  应用侧（`eu.kanade.tachiyomi.modelpack.*` + `util.waifu2x.*`），包里没有一行。
- **桌面沙盒今天连解析都过不去**，而且是**先死在解析、后死在身份**两道坎上：

  | 坎 | 实测结果 | 触发条件 |
  |---|---|---|
  | ① 读 manifest | `java.nio.BufferUnderflowException` | 包的 `resources.arsc` 是 40 字节空表（`packageCount=0`），apk-parser 2.6.10 会无条件读第 3 个 chunk header，越界 |
  | ② 判身份 | `IllegalStateException: no tachiyomi.extension.class meta-data` | 资源表正常之后才会走到这里，**这道坎的行为符合设计** |

  也就是说今天丢一个包进 `extensions/apk/`，失败原因会被记成「APK 坏了」，而不是
  「这不是扩展」——**归因是错的**，这一点比「不支持」更值得修。
- **但基线不受影响。** 10 个包进去只贡献 `failures[]` 非空，`extensions` / `sources` 计数不变，
  192/192 加载率仍是 100%。真正的破坏是台架「`failures: []`」这条不变量变脏。
- **真正挡住「支持」的不是加载面，是执行面。** `libwaifu2x-jni.so` 静态链了
  ncnn + glslang/Vulkan 与整套 Qualcomm QNN，且宿主 APK **只有 arm64-v8a**（25 个 `.so`、
  解压 142.5 MB）。桌面 x86-64 JVM 上既没有这个 ABI，也没有这套后端。
  相比之下「识别一个包」几乎是免费的 —— 见 §五 L1。
- **不建议**在沙盒里 `dlopen` 包里的 `libmodelpack.so`：那是 ELF64 AArch64
  （`e_machine=0xB7`），Windows 的 PE 加载器根本不认 ELF，Linux x86-64 也会因机器类型不符拒收。

## 一、样本构成

10 个包，两副形态：

| 形态 | 包 | 载荷 |
|---|---|---|
| **A：纯权重**（8 个） | `waifu2x`、`waifu2x-upconv7`、`realcugan`、`realcugan-pro`、`realcugan-nose`、`realesrgan`、`span-nomosuni`、`sudo-ultracompact` | 只有 `assets/<assetPath>/*.bin` + `*.param`（ncnn 格式），无 native 库 |
| **B：自带 CPU 实现**（2 个） | `acnet`、`anime4k` | `assets/` 里只有 `modelpack.json`（642/653 B）；**权重和引擎都在 `lib/arm64-v8a/libmodelpack.so`（74,200 B）里** |

形态 B 的权重是编进 `.so` 的（`ACNET_WEIGHTS` / `src/main/cpp/acnet_weights_f8b4.h`），
Anime4K 则是手写的边缘导向滤波，所以 74 KB 就装下了整个引擎。
这也解释了 manifest 里只有这两个包带 `extractNativeLibs=true`。

体积：`realcugan` 29.1 MB 最大，`waifu2x` 25.0 MB，两个形态 B 各 41 KB。

### 权重确实是 ncnn

```
.param 首行 = 7767517          # ncnn param magic
第二行      = 59 71            # 层数 / blob 数
Convolution Convolution1 1 1 Input1 Convolution1_ReLU1 0=32 1=3 5=1 6=864 9=2 -23310
```

`0=`/`1=`/`5=`/`6=`/`9=` 是 ncnn 的参数编号（输出通道/卷积核/空洞/权重字节数…），
`.bin` 是裸 float 数据、无 magic —— 与 ncnn 一致。

### manifest 契约（10 个完全同形）

```
package            com.tachiyomix.modelpack.<id>
versionCode/Name   6 / 1.3        minSdk 26 / targetSdk 36 / compileSdk 37
<uses-feature name="tachiyomix.modelpack" required="false">
<application hasCode="false" label="…">
  <meta-data name="tachiyomix.modelpack.id"   value="<id>">
  <meta-data name="tachiyomix.modelpack.name" value="…">
```

### 描述符 `assets/modelpack.json`（`schemaVersion: 1`）

自描述、带版本号，每个包一份：

- `models[]`：`key` / `name` / `engine` / `assetPath` / `scales` / `denoiseLevels` /
  `precisions`（恒 `[0,1,2,3]`）/ `supportsNpu` / `npuScales` / `styles` / `defaults`
- `options`（10 个包**只差一个字段**）：`preloadPages [1,2,3,5,8]`、`gpuPerformanceModes [0,1,2]`、
  `tileSizes [64,96,128,192,256]`、`maxResolution 0×0`（不限）、`fp16Arithmetic true` 全同；
  `maxProcessingResolution` 9 个是 1600×1600，**`anime4k` 是 0×0**（唯一例外）

`supportsNpu` 为 true 的 4 个：`realcugan`、`realcugan-pro`、`realesrgan`、`span-nomosuni`。

## 二、桌面沙盒今天拿它们怎么办（实测）

装置：一个**只放这 10 个包的隔离台架根**（与 192 个扩展的基线台架分开，端口 4598），
待测 jar 用台架固定的那份 `ext-runtime.jar`（26,031,175 B，
`stub.exclude.resources=true` / `strip.revision=2` / `36.r02.2`，即 P0 之后的产物）。
把 10 个包拷进 `extensions/apk/`，`POST /reload`：

```json
{"ok":true,"extensions":0,"sources":0,"failures":[
  {"apk":"TachiyomiX-modelpack-acnet-release.apk","error":"java.nio.BufferUnderflowException"},
  … 共 10 条，错误全同
]}
```

堆栈（`logs/sandbox.log`）：

```
java.nio.BufferUnderflowException
  at net.dongliu.apk.parser.parser.ResourceTableParser.readChunkHeader(ResourceTableParser.java:167)
  at net.dongliu.apk.parser.parser.ResourceTableParser.parse(ResourceTableParser.java:60)
  at net.dongliu.apk.parser.AbstractApkFile.parseResourceTable(AbstractApkFile.java:391)
  at net.dongliu.apk.parser.AbstractApkFile.parseManifest(AbstractApkFile.java:188)
  at net.dongliu.apk.parser.AbstractApkFile.getApkMeta(AbstractApkFile.java:69)
  at sandbox.ExtensionRegistry.readApkInfo(ExtensionRegistry.kt:174)
```

### 坎①的根因：40 字节的空资源表撞上 apk-parser 的越界读

包的 `resources.arsc` 只有 **40 字节**：

```
0200 0c00 28000000 00000000      RES_TABLE   headerSize=12 size=40 packageCount=0
0100 1c00 1c000000 00000000 …    RES_STRING_POOL headerSize=28 size=28（空池）
```

对比正常扩展（`tachiyomi-all.ahottie-v1.6.4.apk`）：**1,052 字节，`packageCount=1`**。
包之所以是空表，是因为它们 `hasCode=false`、没有 `res/` 目录、`label` 是字面量而非
`@string` 引用 —— AAPT2 于是只发一个「表头 + 空字符串池」的最小表。

`javap -c` 看 apk-parser 2.6.10 的 `ResourceTableParser.parse()`：

```
  0: readChunkHeader() → ResourceTableHeader      // 表头
  8: readChunkHeader() → StringPoolHeader         // 全局字符串池
 48: readChunkHeader() → PackageHeader            // ← 无条件，在循环判断之前
 58: if (i >= tableHeader.getPackageCount()) return;
```

**第 3 个 `readChunkHeader()` 在 `packageCount` 判断之前**。正常 APK 里偏移 40 处还有
package chunk，读得动；空表到 40 字节就到底了，`readUShort` 直接抛 `BufferUnderflowException`。
而 `AbstractApkFile.parseManifest()` 的**第一条指令**就是 `parseResourceTable()`，没有条件分支 ——
所以这条路对任何 APK 都是必经的。

**两个对照实验**（这是判据，不是推测）：

| 对照 | 构造 | 结果 | 说明 |
|---|---|---|---|
| A | 拿**正常扩展** `ahottie`，只把 `resources.arsc` 换成那 40 字节空表 | `java.nio.BufferUnderflowException` | **空表是唯一触发条件**，与包本身无关 |
| B | 拿 `waifu2x` 包，只把 `resources.arsc` 换成借来的正常 1,052 字节表 | `IllegalStateException: no tachiyomi.extension.class meta-data` | 过了坎①，**坎②按设计正确拒绝** |

`POST /inspect` 走同一条路，对原始包返回
`{"error":"java.nio.BufferUnderflowException"}`（400）—— 连 `not a tachiyomi extension
(missing tachiyomi.extension.class)` 那句都到不了。

### 坎②：身份判定

资源表正常之后才轮到 [`ExtensionRegistry.kt:178`](../../../ext-runtime/src/main/kotlin/sandbox/ExtensionRegistry.kt)：

```kotlin
val declared = metaValue(manifest, "tachiyomi.extension.class")
    ?: throw IllegalStateException("no tachiyomi.extension.class meta-data")
```

包没有这条 meta-data，`loadApkSafely` 把它记进 `failures[]`。
这条路径是**对的**：拒绝得干净、原因准确、不推进 extensionId、号段不留空洞。

### 对现有基线的影响

| 不变量 | 丢包进去之后 |
|---|---|
| `/reload` 返回 200 | 不变 |
| `extensions` / `sources` 计数 | 不变（192 / 1168） |
| `ext_survey` 加载率 | 不变（192/192） |
| `failures: []` | **变脏**（多 10 条，且原因是错的） |

结论：**不是加载率回归，是闸门污染**。危险在于「包坏了」这个错误归因会误导排查 ——
下次真有个扩展因为资源表畸形而失败，会被当成同一类噪声忽略掉。

## 三、与扩展机制的差异

| 维度 | Tachiyomi 扩展 | TachiyomiX modelpack |
|---|---|---|
| 身份标记 | `<meta-data name="tachiyomi.extension.class">` | `<uses-feature name="tachiyomix.modelpack">` + `.id` / `.name` meta-data |
| 代码 | `classes.dex` 里有 `Source` 实现，dex2jar 后加载 | `hasCode=false`；形态 B 的可执行体是 **`.so` 不是 dex** |
| 载荷 | 无（顶多几张字典表） | `assets/` 下几 MB～29 MB 的 ncnn 权重 |
| 描述符 | 无（类名即契约） | `assets/modelpack.json`，`schemaVersion` 版本化 |
| 入口协议 | JVM 反射调 `Source` 方法 | **C ABI 6 个符号**，宿主 `dlopen` |
| 与宿主的耦合 | 松（HTTP 抓取，扩展自己干活） | **紧**：推理后端在宿主 `.so` 里，包只供权重 |
| 消费方 | 沙盒自己（`SourceRegistry` → HTTP） | 宿主应用的阅读器 UI（`ReaderEnhancement` / `ModelPacksScreen`） |

一句话：扩展是**插件**，modelpack 是**资源 + 可选的小引擎**，消费方是宿主应用而不是沙盒。
它们共用「APK 当分发容器」这一层壳，底下没有任何共同抽象。

## 四、执行面的真实差距

### 包侧 ABI（实测 `libmodelpack.so` 的 `.dynsym`）

ELF64 AArch64（`e_machine=0xB7`），74,200 B，**恰好导出 6 个符号**：

```
modelpack_abi_version            8 B
modelpack_describe              12 B
modelpack_init                 292 B
modelpack_process            7,888 B
modelpack_release                4 B
modelpack_set_progress_callback 12 B
```

**只依赖 libc**（`DT_NEEDED: libc.so / libdl.so / libm.so`；导入 `malloc`/`free`/`calloc`/
`memcpy`/`strlen`/`strstr`/`strtol`/`fprintf`/`__memcpy_chk`/`__stack_chk_fail`/`__cxa_atexit`/
`__cxa_finalize`/`__register_atfork` + `stderr`）—— 没有 ncnn、没有 Vulkan、没有 libc++。
重活全部回抛给宿主，这也是 `modelpack_set_progress_callback` 存在的原因。

从字符串还能还原出契约形状：

- `modelpack_describe` 返回**内联 JSON**（`.so` 里能读到它自己的
  `{"schemaVersion":1,"id":"anime4k-acnet","models":[…]}`）
- `modelpack_init(…, options_json)` **用 JSON 传选项**（`json_get_int(options_json,"tileSize",…)`、
  `model_key`），失败打 `[modelpack] unknown model_key=%s` 到 stderr
- `modelpack_process` 是 RGBA8 进出、内部按 tile 切（`g_tile_size` / `total_tiles` /
  `report_progress`）
- 回调类型名 `modelpack_progress_fn`
- 宿主侧校验失败的报错：`Model pack library %s does not implement the modelpack ABI`
- 构建来源（编译进 `.so` 的调试字符串）：NDK 26.1.10909125 / clang 17.0.2 / LLD，
  `-mlgo +pgo +bolt +lto`，上游源码路径 `…/model-packs/src/main/cpp/modelpack.cpp`

### 宿主侧（`TachiyomiX-0.1.3a-arm64-v8a.apk`）

- **25 个 native 库，解压共 142,544,228 B，清一色 `arm64-v8a`**
- `libwaifu2x-jni.so`（9,665,224 B）：静态链 **ncnn**（80 处字符串命中）+ **glslang/SPIR-V**
  （1,413 / 88）+ **Vulkan**（42）+ **QNN**（97）；31 个 `Java_*` JNI 入口
- Qualcomm QNN 全套：`libQnnCpu.so`、`libQnnHtp.so`、`libQnnHtpV{69,73,75,79,81}{Skel,Stub}.so`、
  `libQnnModelDlc.so`、`libQnnSystem.so`
- 面向包的 JNI 组 `PackNativeBridge`：`nativeAbiVersion` / `nativeDescribe` / `nativeInit` /
  `nativeLoad` / `nativeProcess` / `nativeRelease` —— 其中 `nativeAbiVersion` 是一次
  **ABI 版本握手**，说明这套契约本身是版本化设计的
- Java 侧 13 个 `eu.kanade.tachiyomi.modelpack.*`（`DescriptorParser` 用 kotlinx.serialization
  解 `modelpack.json`）+ 18 个 `eu.kanade.tachiyomi.util.waifu2x.*`
- 装载序列（从 dex 字符串还原）：
  1. 枚举带 `tachiyomix.modelpack` uses-feature 的包
  2. 读 `assets/modelpack.json` → `ModelPackDescriptor`
     （失败：`Failed to parse modelpack.json of model pack ` / `+ has no modelpack.json; ignoring its models`）
  3. 有 `lib/<abi>/libmodelpack.so` → 解出来 `System.load` → 走 `PackNativeBridge`
     （失败：`#: failed to extract libmodelpack.so`）
  4. 没有 → `1 ships no libmodelpack.so; using built-in engines`，用 `libwaifu2x-jni.so`
     里的内置引擎直接吃 ncnn 权重

### 桌面缺的是什么

**不是** `android.graphics`。`android-compat` 已有 `Bitmap` / `BitmapFactory` / `Canvas` / `Paint`，
形态 B 那种 RGBA8 进出的胶水在桌面 JVM 上不是障碍。

缺的是**推理后端本身**：ncnn + Vulkan + QNN 全在 arm64 的 `libwaifu2x-jni.so` 里，
且没有 x86-64 变体。另外 `android-compat` 里 `ApplicationInfo.nativeLibraryDir`
（`ApplicationInfo.java:597`）**声明了但从未被赋值** —— 桌面侧根本没有「解 native 库」
这一步（`PackageUtil.kt` 只填 `sourceDir`）。

## 五、若将来要支持：最小可行路径

按「先不误判 → 再识别 → 最后才谈执行」分层，前两层都很便宜。

**L0 —— 修掉错误归因（成本最低，建议无论支不支持都做）**

坎① 不是「APK 坏了」，是「资源表是空表」。两种改法：

- 在 `readApkInfo` 交给 apk-parser 之前，先按 chunk 走一遍 `resources.arsc`，
  `packageCount == 0` 就短路（不依赖 apk-parser 修）；
- 或者 catch `BufferUnderflowException`，报成
  `unreadable resource table (empty RES_TABLE)` 这类**说清事实**的原因。

收益：`failures[]` 里的噪声可解释；将来真有扩展因畸形资源表失败时不会被淹掉。

**L1 —— 识别与展示（近乎免费，因为管道已经铺好了）**

`android-compat` 已经把需要的东西都填好了：

| 需要读的 | 现状 |
|---|---|
| `uses-feature tachiyomix.modelpack` | **已填** —— `PackageUtil.kt:15` 把 `usesFeatures` 映射成 `PackageInfo.reqFeatures` |
| `tachiyomix.modelpack.id` / `.name` | **已填** —— `InstalledPackage.kt:35` 把所有 `<meta-data>` 塞进 `ApplicationInfo.metaData` |
| `assets/modelpack.json` | 可读 —— `sourceDir` 已指向 APK（`PackageUtil.kt:27`），`ZipFile` 取一条即可 |
| `lib/arm64-v8a/libmodelpack.so` | **缺** —— `nativeLibraryDir` 未赋值，也没有解 `lib/` 的步骤 |

也就是说「列出已安装的 modelpack、显示 id/name/支持的 scale」只差一个 uses-feature 判断和
一次 zip 读取。注意 `ExtensionLoader.isJarResource()` 已经**保留 `assets/**`、排除 `lib/`**
（`ExtensionLoader.kt:390`），所以万一将来把包转成 jar，权重会跟着走、`.so` 不会 —— 这条规则不用改。

**L2 —— 执行（这是墙）**

只有两条路，都要在**宿主侧**做，不是在沙盒里：

1. **宿主提供推理服务**：桌面/Rust 侧另起一个能吃 ncnn 权重、跑 x86-64 的引擎。
   注意这不是「复用 `libwaifu2x-jni.so`」——那是 arm64-only，等于**新写一个后端**。
   好在 ncnn 本身有 x86-64 构建，且 `.bin`/`.param` 是标准格式，格式这层没有障碍。
2. **只做 Android**：桌面沙盒明确不碰执行，只保留 L0/L1。

**明确不做**：在沙盒里 `System.load` 包里那份 `libmodelpack.so`。它按 ELF 头就是
`ELF64 / e_machine=0xB7 (AArch64)`，Windows 的 PE 加载器不认 ELF，Linux x86-64 也会因
机器类型不符拒收。形态 A 的 8 个包更直接 —— 它们连 `.so` 都没有。

## 六、待办 / 决策点

- [ ] **L0 是否做**（2026-09-30 未定）：`resources.arsc` 空表的短路或错误归因修正。
      倾向做 —— 与 modelpack 支不支持无关，它本身是个归因缺陷。
- [ ] 若做 L0，要不要顺手在 `ExtensionRegistry` 里加一条「识别为 modelpack 就记成
      `skipped`（而非 `failed`）」的分类，避免台架 `failures: []` 被污染。**需要先定
      `/reload` 的响应格式是否加 `skipped` 字段** —— 这会动到 server 侧的解析。
- [ ] L1 只在「桌面端要展示 modelpack 列表」时才做。目前没有这个需求。
- [ ] L2 不排期。

## 七、可复现的侦察命令

结论都可重放。下面所有路径都是**相对/占位**的，按各自环境替换：

```bash
# AXML（二进制 manifest）转储器 —— 沙盒里没有 strings，只能自己写（约 130 行）
python axml.py <apk-or-xml> [--elements]

# 10 个包的 manifest + dex 类型 + 资产清单（脚本接收目录参数）
python mpdump.py  "<含 10 个 modelpack 的目录>"
python mpassets.py <同上目录>
python mpjson.py   <同上目录>          # 全部 modelpack.json

# 宿主 native 库的字符串普查 + JNI 入口提取
#   libwaifu2x-jni.so：ncnn / glslang / vulkan / qnn 命中数、31 个 Java_* 入口

# 包侧 ABI：手解 ELF64 .dynsym（无 readelf）
#   libmodelpack.so → 6 个导出符号 + DT_NEEDED libc.so/libdl.so/libm.so

# apk-parser 行为：javap 反汇编
javap -p -c -classpath apk-parser-2.6.10.jar \
      net.dongliu.apk.parser.parser.ResourceTableParser
javap -p -c -classpath apk-parser-2.6.10.jar net.dongliu.apk.parser.AbstractApkFile

# 桌面沙盒实测（用只放 modelpack 的隔离根，与 192 基线台架分开）
python ext_probe.py --root <隔离根> --port 4598 --serve
curl -s --noproxy '*' -X POST http://127.0.0.1:4598/reload
curl -s --noproxy '*' -X POST --data-binary @<某个 modelpack>.apk http://127.0.0.1:4598/inspect
python ext_probe.py --root <隔离根> --stop
```

`ext_probe.py` 会从台架约定的固定位置拷 jar 进隔离根，
所以**换台架根不需要换 jar**，测的仍是当前产物。

## 维护约定

同 [`README.md`](README.md)：已定的选型连日期一起留着；落地之后不删文档、把结果补成一节；
与代码现状对不上的口径当场改掉。
