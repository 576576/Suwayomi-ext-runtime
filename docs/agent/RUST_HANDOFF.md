# 沙盒状态归谁：config 抽离（已做）+ SQLite / 偏好落盘的评估（2026-09-30）

判断原则一句话：**扩展会不会同步调用它**。扩展代码在沙盒 JVM 里同步跑，凡是扩展
直接调用到的东西都不能搬到 Rust；只有「宿主启动时的一次性配置」搬得走。

---

## 一、config 子系统抽给 Rust —— 已落地

### 摸底时的三个关键发现

1. **`ConfigManager` 是只读的**：`updateValue` / `resetUserConfig` / `updateUserConfig` /
   `getRedactedConfig` 全仓无人调用 → 沙盒从不写 `server.conf`，不存在"沙盒自己维护一份配置"。
2. **沙盒不自己滚日志**：`initLoggerConfig`（logback rolling file appender）无人调用，
   Rust 侧 `spawn_java` 已经把 stdout/stderr 重定向到 `<appdata>/logs/sandbox.log`。
   所以没有"两条日志各写一份"。
3. **真正的分裂在 `ApplicationRootDir`**：它是
   `System.getProperty("suwayomi.tachidesk.config.server.rootDir", AppDirs{appName="Tachidesk"}.getUserDataDir())`
   —— 兜底走 `ca.gosyer:kotlin-multiplatform-appdirs` 推导的**平台用户数据目录**
   （Windows 下 `%APPDATA%\Tachidesk`）。而 `33610de` 之后扩展目录 / dex2jar 产物目录 /
   设置目录都由 `SUWAYOMI_APPDATA_DIR` 派生。结果就是
   **`AndroidFiles` 的 15 个目录（dataDir / filesDir / databasesDir / prefsDir …）落在另一个根上**，
   扩展用 `context.filesDir`、`context.getDatabasePath()` 写的东西和 Rust 侧看到的不是同一棵树。
   而且 `AndroidFiles.registerFile()` 每次访问都 `mkdirs()`，目录是真会被建出来的。

### 做了什么

**P0 —— 根由 Rust 侧决定**（`android-compat/config/…/ApplicationRootDir.kt`）：

```
-Dsuwayomi.tachidesk.config.server.rootDir  →  $SUWAYOMI_APPDATA_DIR  →  "appdata"
```

第三档从 AppDirs 改成字面量 `appdata`，与 `Main.kt` 对同一环境变量的默认值保持一致
（这样直接 `java -jar` 不带环境变量时，也不会出现两个根）。
`ca.gosyer:kotlin-multiplatform-appdirs` 的三处依赖声明全部移除。

**P1 —— 每个配置项可被 Rust 侧精确覆盖**：三个模块从 `ConfigModule` 改为继承
`SystemPropertyOverridableConfigModule`（config 模块本来就带这个基类，只是没人用），
属性委托从 `by getConfig()` 换成 `by overridableConfig`：

- `FilesConfigModule` → `-Dsuwayomi.tachidesk.config.android.files.<属性>`
- `ApplicationInfoConfigModule` → `…android.app.<属性>`
- `SystemConfigModule` → `…android.system.<属性>`（只有 `isDebuggable`；
  `properties.*` 那套动态键仍只读 `compat-reference.conf`）

不传 `-D` 时行为与之前完全一致（走 `compat-reference.conf`）。

### 实测证据

用探针直接读 `AndroidFiles`（`javac -cp ext-runtime.jar` 编译，三种启动方式各跑一次）：

| 启动方式 | `rootDir` |
| --- | --- |
| `SUWAYOMI_APPDATA_DIR=…/sbx-appdata` | `…\sbx-appdata\android-compat\appdata` ✅ 与 Rust 侧同根 |
| 什么都不给 | `appdata\android-compat\appdata`（与 `Main.kt` 一致） |
| 上面再加 `-D…android.files.filesDir=…\OVERRIDDEN-files` | `filesDir` 变成 `…\OVERRIDDEN-files`，其余照旧 ✅ |

另外起真实沙盒进程（`SUWAYOMI_SANDBOX_PORT=4799`）冒烟：`/health` 返回
`{"ok":true,"extensions":0,"sources":0}`，`/extensions`、`/sources` 正常。
（本机没有扩展台架，批量加载率这轮没法测——见下"待办"。）

### Rust 侧（Suwayomi-next）要做什么

- **什么都不做也已经生效**：`spawn_java` 早就在传 `SUWAYOMI_APPDATA_DIR`（`sandbox.rs:667`），
  P0 之后 Android 目录自动跟过去。
- **可选**：在 `spawn_java` 里加 `-Dsuwayomi.tachidesk.config.android.*` 逐项下发。
  它已经在传 `-Djava.net.useSystemProxies=true`，加参数是零成本的。
- 升级后要留意：`<appdata>/server.conf`（若存在）会被 `ConfigManager` 当作 user config 合并。

### 为什么没有继续去掉 `typesafe-config` / `config4k`

`config4k` 用反射把 HOCON 读成属性委托；去掉它就要手写 19 个属性
（Files 15 + app 2 + system 1 + `server.debugLogsEnabled`）的解析，外加
`SystemProperties` 那套动态键。省两个依赖换一堆手写得不偿失。
**HOCON 现在的角色是"默认值来源"，不是"配置所有者"——所有者已经是 Rust 侧。**

---

## 二、SQLite 能不能外包给 Rust 侧已有数据库实现？

**结论：不能，也不该。** 而且"两边各写数据库"这个担心不成立。

1. **不是同一份数据**。扩展通过 `android.database.sqlite.*` 建的是**它自己的私有库**——
   schema 由扩展定，存的是缓存、历史、书签之类的东西。Rust 侧
   `suwayomi-db`（`rusqlite` 0.40 / 可选 Postgres）是**业务库**：manga / chapter / category，
   固定 schema。两者根本不重叠，谈不上双写。
2. **同步 API + 逐行游标 = RPC 灾难**。AndroidCompat 的 `SQLiteDatabase` 走 JDBC
   （`ScrollableResultSet` 持有 JDBC `Connection` / `PreparedStatement`）。扩展一次
   `db.query()` 之后是**逐行** `moveToNext()` / `getString(i)`。改成跨进程 RPC 就是
   每次 `query` 一次往返、每次 `moveToNext` 一次往返——一个源刷一页就上千次往返，
   十几个源并发直接打满线程池。
3. **语义对不齐**：事务、`PRAGMA`、`ATTACH`、自定义 collator / 函数都要跨进程保持，
   等于要在 Rust 侧维护 per-connection 状态机，还要处理沙盒重启后的连接恢复。
4. **真问题不是"谁写"，是"写在哪"**：改动前扩展的数据库落在 AppDirs 根下，
   备份 / 迁移 / 卸载清理都会漏掉它。**P0 已经修好**——现在它在
   `<appdata>/android-compat/appdata/databases/` 下，和 Rust 侧同一棵树。

**如果还想进一步**：让沙盒通过 `/jvm`（或新端点）把"扩展数据库目录"报给 Rust，
让备份/清理知道它的存在；但**读写仍留在沙盒内**。这是成本最低的正确做法。

---

## 三、偏好落盘能不能经 Rust 中转？

**结论：能，但建议不动。**

现状查证（在 `Suwayomi-next` 里 grep 过）：

- 写者是**唯一的**：沙盒的 `FilePreferences` 写 `<appdata>/settings/source_<id>.properties`。
- Rust 侧**从不直接碰那个文件**（`crates/**/*.rs` 里没有 `.properties` / `shared_prefs` /
  `source_` 的文件写入），它只走 HTTP 代理：
  `GET/POST /source/{id}/preferences`（设置界面 JSON）与
  `GET/POST /source/{id}/preferences/raw`（扁平 key/value，备份 105 号段用）。
- 所以**不存在两边各写**——Rust 侧是"读代理 + 恢复时通过沙盒回写"。

改成 Rust 中转的代价：

- `SharedPreferences` 是**同步** API。扩展渲染设置页、每次请求前读凭据
  （`getSourcePreferences()` → `getString("username")`）都会调，一次请求几十次 get。
  中转 = 每次 get 一次 HTTP 往返。
- 还要新增端点、处理多实例一致性、宕机丢最后一次写入、`/reload` 时的语义。

若将来真要统一持久化所有权，唯一合理的形态是：
**启动时从 Rust 拉全量快照进内存 → 本地同步读写 → 变更异步推回 Rust**。
换来的是"备份/恢复不再经过沙盒"和"多实例一致"，代价是上面那些复杂度。

**当前不值得做。** 真要动手，先做成开关灰度，并用 `ext_usability.py` 的可用率做判据。

---

## 四、待办

- 本机没有扩展台架（`E:\Github\Suwayomi-builds\ext-lab` 不存在），
  **这一轮没跑批量加载率**。config 改动影响的是目录落点，风险点是某些扩展
  假定 `filesDir` 可写——合入前补一轮 `ext_survey.py`（192 包）比较稳妥。
- `androidcompat.rootDir` 现在是 `<root>/android-compat`，`android.files.rootDir` 又是
  `${androidcompat.rootDir}/appdata`，出现 `android-compat/appdata` 双层嵌套。
  想收拾可以把 `compat-reference.conf` 里那层去掉，但会让已部署实例换目录 → 需要迁移，暂不动。
