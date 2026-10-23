package convention

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

/**
 * 约定插件：`id("app.android.compose")`
 *
 * 必须在 `app.android.library` / `app.android.application` **之后**应用 ——
 * 它要给已存在的 Android 扩展打开 compose 开关。
 *
 * 这里同时负责两件事：
 *  1. 应用 Kotlin 的 Compose 编译器插件（Kotlin 2.0 起编译器随 Kotlin 版本发布，
 *     版本号与 libs.versions.toml 里的 `kotlin` 一致，别再单独找 composeOptions）。
 *  2. 打开 buildFeatures.compose。
 *
 * 不需要 Compose 的模块（如 :core-network）**不要**加这个插件，
 * 少一层编译器插件，增量编译会快一截。
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        // library 与 application 两种宿主，谁在就配谁
        extensions.findByType(LibraryExtension::class.java)?.apply {
            buildFeatures { compose = true }
        }
        extensions.findByType(ApplicationExtension::class.java)?.apply {
            buildFeatures { compose = true }
        }

        // ⚠️ 必须显式收尾成 Unit：`Plugin.apply` 返回 Unit，而 `= with(target) { ... }`
        //    会把**块内最后一句的值**透出去（这里是 `ApplicationExtension?`）→ 编译报
        //    "Return type ... is not a subtype of the return type of the overridden member"。
        //    块体里最后一句是 Unit（如 configureKotlinAndroid()）则不会触发，所以只有这个插件中招。
        Unit
    }
}
