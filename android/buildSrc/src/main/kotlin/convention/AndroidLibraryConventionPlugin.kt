package convention

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 约定插件：`id("app.android.library")`
 *
 * 用于 core-* 与 feature-* 模块。模块自己的 build.gradle.kts 只需要写
 * namespace 和 dependencies，SDK 版本、JVM 版本、buildFeatures 全部由这里统一。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        // ⚠️ 同 AndroidApplicationConventionPlugin：AGP 9 内置 Kotlin，
        //    不要再 apply `org.jetbrains.kotlin.android`，jvmTarget 走 compileOptions 即可。

        extensions.configure<LibraryExtension> {
            // ⚠️ 同 Application 插件：AGP 9 要用带 minorApiLevel 的新写法
            compileSdk {
                version = release(Config.COMPILE_SDK) {
                    minorApiLevel = Config.COMPILE_SDK_MINOR
                }
            }

            defaultConfig {
                minSdk = Config.MIN_SDK
                // library 不设 targetSdk（由宿主 application 决定），AGP 会忽略它
                //
                // ⚠️⚠️ 这里**不要**写 `consumerProguardFiles("consumer-rules.pro")`。
                //    2026-10-10 引入 perf 变体、任务图重算时才炸出来（此前长期被任务
                //    UP-TO-DATE 缓存掩盖，所以 assembleDebug 一直「看着正常」）：
                //      该声明会让**每一个 library 变体**都跑 `mergeXxxConsumerProguardFiles`
                //      并**校验文件存在**，而 13 个模块里只有 feature-assistant 建了这个
                //      文件 → `Supplied consumer proguard configuration does not exist:
                //      ...\core-ui\consumer-rules.pro`（debug / release 变体都会踩）。
                //    而且唯一那个文件（feature-assistant/consumer-rules.pro）**内容全是注释、
                //    没有任何实际 keep 规则** —— 这套 consumer 规则机制在本项目里本就是空转。
                //    删掉它，所有模块（debug / release / perf）都不再需要这个文件。
                //    将来真要给某模块加 keep 规则，在**那个模块自己**的 build.gradle.kts 里
                //    写 `consumerProguardFiles("consumer-rules.pro")`，不要放回这里。
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            buildFeatures {
                // 默认关掉 BuildConfig：绝大多数模块用不到，开着会拖慢增量编译。
                // 需要它的模块（如 core-network 判断 debug 日志）自己开。
                buildConfig = false
            }

            // 单元测试用 JUnit4（本地跑，不需要真机）
            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }
    }
}
