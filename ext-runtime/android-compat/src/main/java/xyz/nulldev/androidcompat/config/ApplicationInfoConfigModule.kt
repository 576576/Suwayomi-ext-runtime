package xyz.nulldev.androidcompat.config

import com.typesafe.config.Config
import io.github.config4k.getValue
import xyz.nulldev.ts.config.SystemPropertyOverridableConfigModule

/**
 * Application info config.
 *
 * 每项都能被 `-Dsuwayomi.tachidesk.config.android.app.<属性名>=<值>` 覆盖。
 */

class ApplicationInfoConfigModule(
    getConfig: () -> Config,
) : SystemPropertyOverridableConfigModule(getConfig, "android.app") {
    val packageName: String by overridableConfig
    val debug: Boolean by overridableConfig

    companion object {
        fun register(config: Config) = ApplicationInfoConfigModule { config.getConfig("android.app") }
    }
}
