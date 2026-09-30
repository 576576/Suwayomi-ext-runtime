package xyz.nulldev.androidcompat.config

import com.typesafe.config.Config
import io.github.config4k.getValue
import xyz.nulldev.ts.config.SystemPropertyOverridableConfigModule

/**
 * `android.os.Build` / `android.os.SystemProperties` 的静态初始化会直接摸这个模块，
 * 所以它在沙盒启动时就必须注册好（见 `sandbox.AndroidEnv.registerAndroidCompatConfig`）。
 *
 * `isDebuggable` 可被 `-Dsuwayomi.tachidesk.config.android.system.isDebuggable=<bool>` 覆盖；
 * `properties.*` 那套动态键（`SystemProperties.get`）不走覆盖，仍只读 `compat-reference.conf`。
 */
class SystemConfigModule(
    val getConfig: () -> Config,
) : SystemPropertyOverridableConfigModule(getConfig, "android.system") {
    val isDebuggable: Boolean by overridableConfig

    val propertyPrefix = "properties."

    fun getStringProperty(property: String) = getConfig().getString("$propertyPrefix$property")!!

    fun getIntProperty(property: String) = getConfig().getInt("$propertyPrefix$property")

    fun getLongProperty(property: String) = getConfig().getLong("$propertyPrefix$property")

    fun getBooleanProperty(property: String) = getConfig().getBoolean("$propertyPrefix$property")

    fun hasProperty(property: String) = getConfig().hasPath("$propertyPrefix$property")

    companion object {
        fun register(config: Config) = SystemConfigModule { config.getConfig("android.system") }
    }
}
