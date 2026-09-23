// 扩展运行时（extension runtime）：共享源码树 + 桌面 JVM 沙盒宿主。
// 单一 Gradle 构建，模块目录 `ext-runtime/` 由剥离流程导入（见 docs/EXTRACTION_RECORD.md）。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
    }
}

rootProject.name = "suwayomi-ext-runtime"

include("ext-runtime")
include("ext-runtime:android-compat")
include("ext-runtime:android-compat:config")
include("ext-runtime:android-stub")
