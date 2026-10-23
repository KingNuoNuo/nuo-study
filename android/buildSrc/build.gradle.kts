// convention plugin 的宿主。这里声明的依赖只进「构建脚本的 classpath」，
// 不会被打进任何 APK。
plugins {
    `kotlin-dsl`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

dependencies {
    // ─────────────────────────────────────────────────────────────────────────
    // ⚠️ 这里放什么、不放什么，是有硬约束的，别随手加：
    //
    // buildSrc 的 runtime classpath 会被**自动附加到所有项目的 buildscript classpath**。
    // 于是同一条 classpath 上出现两拨插件，规则不同：
    //
    //  · 本块里 implementation 的插件 → 以「版本未知」的形态出现在 classpath 上。
    //    所以它们**不能**再被 alias(libs.plugins.xxx) 带版本请求，否则报
    //      "the plugin is already on the classpath with an unknown version"
    //    → AGP / Kotlin / Compose 属于这一类：它们只由 convention plugin 用
    //      pluginManager.apply("<id>") 按 ID 应用（不带版本请求），所以放这里安全。
    //
    //  · KSP / Hilt 属于另一类：13 个模块全部用 alias(libs.plugins.ksp/.hilt) 显式声明，
    //    带着版本请求。所以它们**必须不在这里**，交给各模块自己解析。
    //    （buildSrc 的代码也确实没用到它们的类，不需要它们参与编译。）
    //
    //  同理：根 build.gradle.kts 里也不能 alias 声明 AGP/Kotlin/Compose。
    // ─────────────────────────────────────────────────────────────────────────
    implementation(libs.android.gradlePlugin)   // ApplicationExtension / LibraryExtension
    implementation(libs.kotlin.gradlePlugin)    // KotlinAndroidProjectExtension / JvmTarget
    implementation(libs.compose.gradlePlugin)   // 按 ID 应用，但类必须在 classpath 上
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "app.android.library"
            implementationClass = "convention.AndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "app.android.application"
            implementationClass = "convention.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "app.android.compose"
            implementationClass = "convention.AndroidComposeConventionPlugin"
        }
    }
}
