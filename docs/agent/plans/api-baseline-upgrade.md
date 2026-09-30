# Android 公开 API 基线 30 → 36（分支 `dump/api-36`）

> 2026-09-30 记录，**一次性迁移记录**：下文是当时这次换基线的实测数据与施工步骤，
> 里面的版本号都是**当时的对照值**，不代表当前状态。
> **当前基线以 [`ext-runtime/android-stub/android-stub.properties`](../../../ext-runtime/android-stub/android-stub.properties)
> 的 pin 为准** —— 除它之外仓库里没有第二处写死基线。
>
> 当时**已实测通过**：改 pin 后 `./gradlew build` 全绿，16 项测试 0 失败、
> `verifyStubDedup` 无同名类。改动已提交。

## 结论先行

这次升级**不需要动任何一行业务代码**。API 30 → 36 之间被删掉的公开类只有 10 个，
全部在 `android.service.voice` / `android.media` / `android.icu.text` / `android.view.textclassifier`
下，本仓一行都没引用；新增的 1487 个类全是 `throw new RuntimeException("Stub!")` 的空壳，
只在**链接期**被用到（扩展的 dex 链到它们，缺一个就是 `NoClassDefFoundError`，但从不调用）。

所以升级 = 换 `android-stub.properties` 里的 pin。

## 新 pin（已实测）

来源仍是 `dl.google.com`（`android.googlesource.com` 在开发机不可达，见 pin 文件注释）。
仓库索引 `https://dl.google.com/android/repository/repository2-3.xml` 里，
API 36 当时只有 **`platform-36_r02.zip`** 一个修订。

| 项 | API 30（旧） | API 36（新） |
| --- | --- | --- |
| `aospApiLevel` | `30` | `36` |
| `aospPlatformPackageRevision` | `r03` | `r02` |
| 包名 | `platform-30_r03.zip`（52,328,361 B） | `platform-36_r02.zip`（65,878,410 B） |
| `aospPlatformPackageSha256` | `f3f5b757…d84e` | `37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e` |
| `aospPlatformJarSha256` | `96ccfdc8…a424` | `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a` |
| `stripRevision` | `1` | `1`（换基线归 1，原本就是 1） |
| 桩版本号 | `30.r03.1` | `36.r02.1` |

包内路径 `android-36/android.jar`（27,768,026 B）—— 与 API 30 的 `android-11/android.jar`
同一套路，构建脚本里按 `endsWith("android.jar")` 取，不用改。

## 实测 A/B（同一棵树，只换 pin）

| 指标 | API 30 | API 36 | 差 |
| --- | --- | --- | --- |
| `android-stub` 类数 | 3,101 | 4,503 | +1,402 |
| `android-stub-*.jar` | 20,205,450 B | 26,345,409 B | +6.14 MB |
| `ext-runtime.jar` | 45,388,038 B | 51,528,049 B | +6.14 MB（+13.5%） |
| fat jar 类数 | 16,419 | 17,821 | +1,402 |
| `./gradlew build` | SUCCESSFUL | SUCCESSFUL | 16 项测试均过 |

剥离前的全量 jar 对比：4,750 → 6,227 类；**移除 10 个、新增 1,487 个**。
被移除的 10 个（无一个被本仓引用）：

```
android/icu/text/CaseMap$1
android/media/MediaCasException$1
android/media/MediaDrm$HdcpLevel
android/media/MediaDrm$SecurityLevel
android/provider/ContactsContract$1
android/service/voice/AlwaysOnHotwordDetector（含 $Callback / $EventPayload / $ModelParamRange）
android/view/textclassifier/TextClassifierEvent$1
```

新增类的大头：`android.health.connect`（190）、`android.app.appsearch`（78）、
`android.adservices.*`（117）、`android.telephony`（45）—— 全是桌面沙盒永远不会走到的子系统，
纯体积代价。

## 基线字符串的收敛

**当时**基线字符串散落在四处，换 pin 必须一起改，否则 CI 直接红：

- `.github/workflows/build.yml` 校验制品步的日志文案与 `grep` 断言。
- `.github/workflows/release.yml` 头部注释里的示例版本号（不影响行为）。
- `.github/workflows/build.yml` 入参 `description` 里的示例版本号（不影响行为）。
- `README.md` / `docs/en/README.md` 的版本号示例。

**后来已全部去掉**，现在仓库里只有 `android-stub.properties` 一处写死基线：

- `build.yml` 的校验步改成**从 pin 现算**期望值（`sed` 读 `aospApiLevel` /
  `aospPlatformPackageRevision` / `stripRevision`，拼出 `<api>.<包修订>.<剥离修订>`），
  再 `grep -qx "version=$EXPECT"`。换 pin 不用动这一步。
- 注释、`description`、README 里的示例版本号统一换成占位符
  （`<aospApiLevel>.{count/100}.{count%100:02d}`、`<api>.<包修订>.<剥离修订>`、`<版本名>`）。

**从来不用改的**：`release.yml` 的大版本是 `sed` 读 `aospApiLevel` 推出来的，自动跟着变；
`scripts/make-jre.sh` 的模块白名单与 API level 无关；`versionCode = 提交数 + 1000` 不受影响。

## 待办 / 决策点

1. **`aospPrebuiltsCommit` 暂为 `TBD`**。它只进 `META-INF/android-stub.properties` 做溯源，
   不参与下载与校验（`android.googlesource.com` 在开发机不可达，取不到当前基线对应的
   prebuilts/sdk commit）。要么从可达网络补，要么接受留 `TBD`。
2. **`r02` 还是等 `r03`**：Google 的包文件名不可变，出新版会是新文件名（不会原地覆盖），
   所以 `r02` 是安全可 pin 的；将来要升 `r03` 是另一次主动动作。
3. **下游版本号跳变**：大版本跟着基线跳。Suwayomi-next 侧如果有按版本号 pin / 比较的逻辑，
   要确认它认的是「大版本跟着 API 基线走」这条约定，而不是具体数字。
4. **可选瘦身**：新增的 1487 个类里，`android.health.connect` / `adservices` / `appsearch`
   这类子系统几乎不可能被扩展链接到，可在 `android-stub/build.gradle.kts` 里加前缀排除
   换回约 1 MB 体积 —— 但每加一条排除都要承担「某个扩展真的链了它」的风险，
   建议先跑一轮 `ext_survey.py` 看真实缺失清单再决定。

## 验收步骤（按 [`sandbox.md`](../sandbox.md) 的台架）

```bash
# 1. 清缓存看真实警告（增量构建会跳过编译，看不到警告）
./gradlew --no-daemon --console=plain build --rerun-tasks 2>&1 | grep -c '^w:'

# 2. 基线随产物走
unzip -p ext-runtime/build/libs/ext-runtime.jar META-INF/android-stub.properties | grep '^version='

# 3. 批量加载率与自洽性（隔离根 E:\Github\Suwayomi-builds\ext-lab，沙盒 4599）
python ext_survey.py --sandbox http://127.0.0.1:4599 --langs zh,zh-Hans,zh-Hant,all --tag api36
python ext_consistency.py --sandbox http://127.0.0.1:4599 --concurrent 3 --rounds 2

# 4. 真实实例冒烟（手动搬 jar，deploy_latest.py 不管 bin/ext-runtime.jar）
```

第 3 步是**唯一能证明升级无害的证据**：编译通过只能说明本仓没断，不能说明 192 个扩展
在新桩上还链得上（尤其是 `android.webkit` / `android.database.sqlite` 这两块，
它们是 android-compat 自己实现的，与方法签名强相关）。

## 回滚

改动集中在 `ext-runtime/android-stub/android-stub.properties` 一处（当时还连带四处字符串，
现已收敛为「从 pin 推导」）。`git checkout` 掉即可，构建产物（含 AOSP 包）都在 `build/` 下，
不影响工作树。
