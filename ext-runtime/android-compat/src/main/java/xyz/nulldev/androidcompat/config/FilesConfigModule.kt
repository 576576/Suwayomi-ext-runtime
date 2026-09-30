package xyz.nulldev.androidcompat.config

import com.typesafe.config.Config
import io.github.config4k.getValue
import xyz.nulldev.ts.config.SystemPropertyOverridableConfigModule

/**
 * Files configuration modules. Specifies where to store the Android files.
 *
 * 每项都能被 Rust 侧 spawn 本进程时的 `-Dsuwayomi.tachidesk.config.android.files.<属性名>=<值>`
 * 覆盖；不传就照 `compat-reference.conf`（默认值走 `androidcompat.rootDir`，而
 * `androidcompat.rootDir` 又由 [xyz.nulldev.ts.config.ApplicationRootDir] 推出）。
 */

class FilesConfigModule(
    getConfig: () -> Config,
) : SystemPropertyOverridableConfigModule(getConfig, "android.files") {
    val dataDir: String by overridableConfig
    val filesDir: String by overridableConfig
    val noBackupFilesDir: String by overridableConfig
    val externalFilesDirs: MutableList<String> by overridableConfig
    val obbDirs: MutableList<String> by overridableConfig
    val cacheDir: String by overridableConfig
    val codeCacheDir: String by overridableConfig
    val externalCacheDirs: MutableList<String> by overridableConfig
    val externalMediaDirs: MutableList<String> by overridableConfig
    val rootDir: String by overridableConfig
    val externalStorageDir: String by overridableConfig
    val downloadCacheDir: String by overridableConfig
    val databasesDir: String by overridableConfig

    val prefsDir: String by overridableConfig

    val packageDir: String by overridableConfig

    companion object {
        fun register(config: Config) = FilesConfigModule { config.getConfig("android.files") }
    }
}
