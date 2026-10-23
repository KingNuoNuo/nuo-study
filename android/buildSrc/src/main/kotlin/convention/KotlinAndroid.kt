package convention

import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Kotlin 编译基线。
 *
 * ⚠️ jvmTarget 必须与 `compileOptions.targetCompatibility` **完全一致**，
 *    否则只要模块里同时存在 .kt 与 .java，就会报
 *    "Inconsistent JVM-target compatibility detected"。
 *    所以两个 convention plugin 都调这个函数，保证只有一处定义。
 */
internal fun Project.configureKotlinAndroid() {
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(Config.JVM_TARGET.toString()))
        }
    }
}
