// 根构建脚本。**不在这里配置任何模块** —— 所有模块的公共配置走 buildSrc 的
// convention plugin（见 buildSrc/src/main/kotlin/convention/），避免每个模块复制粘贴
// compileSdk / jvmTarget 之类的样板，改一处即可全局生效。
plugins {
    // ⚠️ 只声明「子模块用 alias 请求」的这三个。
    //
    // AGP / Kotlin / Compose **刻意不在这里**：它们由 buildSrc 的 convention plugin
    // （app.android.application / .library / .compose）用 pluginManager.apply("<id>") 按 ID 应用，
    // 而 buildSrc 已把它们的实现放进同一条 buildscript classpath。此处再用 alias 带版本声明，
    // 会因为「类路径上已存在未知版本的同一插件」直接构建失败：
    //   "The request for this plugin could not be satisfied because the plugin is
    //    already on the classpath with an unknown version"
    // 版本由 buildSrc 的 libs.android.gradlePlugin 等统一决定，不需要在这里重复。
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
