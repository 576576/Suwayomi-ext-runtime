package sandbox

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.PreferenceStores
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors

/**
 * Suwayomi extension sandbox — runs Mihon/Tachiyomi extensions in a JVM
 * process isolated from the Rust server. Exposes a stable HTTP/JSON contract:
 *
 *   GET  /health                     -> {"ok":true}
 *   GET  /extensions                 -> [{pkgName, name, lang, versionName, className, sources:[{id,name,lang}]}]
 *   GET  /sources                    -> [{id,name,lang,extension}]
 *   GET  /source/{id}/manga?query=&page=   -> {mangas:[{url,title,thumbnailUrl,...}], hasNextPage}
 *   GET  /source/{id}/manga/{mangaUrl}     -> SManga json
 *   GET  /source/{id}/manga/{mangaUrl}/chapters -> [SChapter json]
 *   GET  /source/{id}/chapter/{chapterUrl}/pages -> [String urls]
 *   GET  /source/{id}/filters         -> [Filter json]
 *   GET  /source/{id}/preferences     -> {preferences:[...]}
 *   POST /source/{id}/preferences     -> {preferences:[...]}
 *
 * 路由与 JSON 契约在 `extension-runtime` 共享；这里只负责**桌面侧**的两件事：
 * 进程入口（读环境变量、扫扩展目录）与 `com.sun.net.httpserver` 宿主。
 */
fun main() {
    val port = System.getenv("SUWAYOMI_SANDBOX_PORT")?.toIntOrNull() ?: 4569
    // 目录只有这一个旋钮：扩展 APK、dex2jar 产物、设置三处都由 appdata 根派生。
    // 子路径必须与 Rust 侧 `AppPaths` 一致（settings/、extensions/apk、extensions/bin），
    // 两侧对不上就会各写一半 —— 改动要同步。
    val appdataDir = Paths.get(System.getenv("SUWAYOMI_APPDATA_DIR") ?: "appdata")
    val extensionsDir = appdataDir.resolve("extensions").resolve("apk")
    // Converted jars live in a separate directory so the extensions dir only holds
    // the downloaded APKs.
    val jarDir = appdataDir.resolve("extensions").resolve("bin")
    Files.createDirectories(extensionsDir)
    Files.createDirectories(jarDir)

    // 扩展把 AppInfo 的版本拼进 User-Agent，值取自宿主（见 installHostVersion）。
    installHostVersion(
        System.getenv("SUWAYOMI_VERSION_CODE"),
        System.getenv("SUWAYOMI_VERSION_NAME"),
    )

    val registry = ExtensionRegistry(extensionsDir, jarDir)

    // 源设置要跨重启保留：扩展填的服务器地址/账号密码存在 `<appdata>/settings/source_<id>.properties`。
    // 不装这个 factory 会退化成进程内存储，重启即丢。
    val settingsDir = appdataDir.resolve("settings")
    Files.createDirectories(settingsDir)
    PreferenceStores.installFactory { key -> FilePreferences(settingsDir.resolve("$key.properties")) }

    // 扫之前先把宿主环境补齐（见 AndroidEnv.kt）：Koin 没起来、主 Looper 没挂、配置模块没注册，
    // 扩展的 <clinit> 都会以 ExceptionInInitializerError 记在类上，之后该类永久不可用。
    setupInjekt()
    startMainLooper()
    installHttpAgent()
    registerAndroidCompatConfig()
    installSandboxContext()
    registry.scan()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    val router = Router(registry)

    // 所有路径都交给共享 Router 做整段匹配（/health、/jvm、/extensions、/sources、
    // /reload、/inspect、/source/{id}/…）。用根 context 而不是逐个 createContext，
    // 避免 `/sources` 与 `/source/` 的 longest-prefix 匹配歧义。
    server.createContext("/") { exchange -> exchange.dispatch(router) }
    // 多线程 executor：默认单线程会把所有请求（含 /health）串行排队——某个
    // 扩展的网络调用阻塞（慢/超时最长 30s）时 health 也卡死，Rust 监视器
    // 误判 sandbox 挂掉而反复 kill/重启。线程池让慢请求独占线程，health 常驻可响应。
    server.executor = Executors.newCachedThreadPool()
    server.start()
    println("suwayomi-jvm-sandbox listening on 127.0.0.1:$port (appdata: $appdataDir, extensions: $extensionsDir, jars: $jarDir)")
}
