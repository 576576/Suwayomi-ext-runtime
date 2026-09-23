// 扩展运行时（extension runtime）：共享源码树 + 桌面 JVM 沙盒宿主。
//
// 模块目录 `ext-runtime/` 由剥离流程 P2（git filter-repo 导入）产出，
// 导入后再放开下面的 include —— 现在放开会因为目录不存在而构建失败。
rootProject.name = "suwayomi-ext-runtime"

// include("ext-runtime")
// include("ext-runtime:android-compat")
// include("ext-runtime:android-compat:config")
// include("ext-runtime:android-stub")
