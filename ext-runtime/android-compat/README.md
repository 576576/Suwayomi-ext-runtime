# android-compat（vendored 源码）

上游：[Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server) 的 `AndroidCompat/` 与
`AndroidCompat/Config/`（Mozilla Public License 2.0，Copyright Contributors to the Suwayomi
project，全文见同目录 `LICENSE`）。

相对上游的改动：

- 去掉 `xyz/nulldev/androidcompat/webkit/` 下的 CEF WebView 实现（`CefHelper`、`KcefHelper`、
  `KcefWebSettings`、`KcefWebViewProvider`）与引用它的 `AndroidCompatInitializer`：
  桌面沙盒不注册 WebView provider，也不带 CEF 运行时。
- 去掉 `resources/font/`（37MB，只被 `android.graphics.Typeface` 读取）。
- `app/cash/quickjs/QuickJs` 由 graalvm polyglot 改为 Rhino 实现。
- 补齐缺失的 `@Deprecated` 注解（AOSP 源码里只写了 Javadoc `@deprecated` 标签、没加注解，
  javac 报 `[dep-ann]`）：`PackageManager.getInstantAppCookieMaxSize()`（含
  `FakePackageManager` 的覆盖）、`Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET`、
  `ConnectivityManager.networkCapabilitiesForType()` / `reportInetCondition()`、
  `NetworkInfo.getReason()`、`WebSettings` 的 `FORCE_DARK_*` 与 `set/getForceDark()`。
  纯注解、零行为变化。
- `ScrollableResultSet` 覆盖 `java.sql.ResultSet` 已弃用方法的 4 个成员补 `@Deprecated`
  （Kotlin `OVERRIDE_DEPRECATION`）。
- 终结器改 `java.lang.ref.Cleaner`（JDK 18 起 `Object.finalize()` 标记待删除，JEP 421）：
  - `SQLiteDatabase`：动作持有 CloseGuard 与 JDBC `Connection`，由 `openInner()` 在连接
    就绪后注册，`dispose()` 时撤销。
  - `MessageQueue`：`mPtr` 由 `long` 改为 `AtomicLong`（native 句柄的独立持有者），
    构造函数注册，`dispose()` 时撤销。顺带把 6 处裸读写改成 `.get()`。
  - `SQLiteCursor`：动作持有 `mQuery`（JDBC `PreparedStatement` 的持有者），构造时注册，
    `close()` 时撤销。
  - `AbstractCursor`：**直接删除** `finalize()`，不换 Cleaner。本仓 `setNotificationUri()`
    直接 `throw NotImplementedError`，`mSelfObserver` / `mContentResolver` 恒为 null，
    原终结器什么都不释放；剩下的 `close()` 只改内部状态，且 Cleaner 动作不能持有宿主、
    无法调用实例方法。将来若真正实现 `setNotificationUri()`，需把 observer 注册状态抽成
    独立对象再挂 Cleaner。
  - 连带：`SQLiteCursor.finalize()` 覆盖的是 `AbstractCursor.finalize()`，基类那个删掉后
    它会变成直接覆盖 `Object.finalize()`、重新触发 removal 警告，所以一并迁。

同步上游时按这份列表重新裁剪。
