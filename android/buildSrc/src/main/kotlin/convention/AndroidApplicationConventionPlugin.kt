package convention

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 约定插件：`id("app.android.application")`
 *
 * 用于 :app-student 与 :app-parent。applicationId / versionCode / versionName
 * 故意留空，由各 App 模块自己声明 —— 两端的版本号必须能独立递增。
 *
 * ⚠️ 孩子端与家长端**不共用 versionCode**。孩子端做题期间绝不能弹出更新提示，
 *    它的发版节奏由家长手动控制；家长端可以随时更新。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        // ⚠️ 这里**不要**再 apply `org.jetbrains.kotlin.android`：
        //    AGP 9.0 起内置 Kotlin 支持且默认开启，再应用该插件会直接构建失败 ——
        //      "The 'org.jetbrains.kotlin.android' plugin is no longer required for
        //       Kotlin support since AGP 9.0."
        //    相应地，Kotlin 的 jvmTarget 也不需要手动配了：内置 Kotlin 下它会默认跟随
        //    `android.compileOptions.targetCompatibility`（见下面的 compileOptions），
        //    所以原先那句 configureKotlinAndroid() 一并删掉，避免两边不一致。

        extensions.configure<ApplicationExtension> {
            // ⚠️ AGP 9 起平台带小版本号（37.0/37.1/37.2…），一个 Int 表达不了，
            //    必须用 `release(x) { minorApiLevel = y }` 这种写法。
            //    写成 `compileSdk = 37` 会去找不存在的 `android-37`，构建报「平台未安装」。
            compileSdk {
                version = release(Config.COMPILE_SDK) {
                    minorApiLevel = Config.COMPILE_SDK_MINOR
                }
            }

            defaultConfig {
                minSdk = Config.MIN_SDK
                targetSdk = Config.TARGET_SDK

                // 单家庭私有部署，不上架应用商店，无需多语言/多密度拆包
                resourceConfigurations += listOf("zh", "en")
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            buildFeatures {
                buildConfig = true   // App 模块要读 BuildConfig.DEBUG 之类
            }

            buildTypes {
                debug {
                    applicationIdSuffix = ".debug"
                    isMinifyEnabled = false
                }

                // ── 「性能档」：真机日常使用的那一档 ────────────────────────────────
                // 与 debug **同签名、同包名**（沿用它 `.debug` 的 applicationIdSuffix），
                // 所以可以直接覆盖安装到现有平板上，孩子端的服务端设置、家庭令牌、
                // 已授予的录音权限全部保留。与 debug 唯一的差别是 **isDebuggable = false**。
                //
                // ⚠️⚠️ 为什么非要单独开一档，而不是直接给 debug 加 `isDebuggable = false`：
                //    debug 那一档平时还要拿来连 adb / 打断点 / 看 Compose 预览，
                //    关了 debuggable 就全废了。两档并存，各管各的。
                //
                // ⚠️⚠️ 为什么非要有「非 debuggable」这一档 —— 这是 2026-10-10 实测出来的，
                //    以前一直被一句想当然的注释盖住：
                //      **debuggable 的包，ART 拒绝做 AOT 编译。**
                //      `adb shell cmd package compile -m speed -f <pkg>` 会打印 `Success`，
                //      但 `cmd package dump <pkg> | grep -A3 'Dexopt state'` 里状态
                //      **永远是 `quicken`**（只有字节码速化 = 解释执行 + JIT）。
                //      症状：install_student.py 里那句「AOT 编译（冷启动 7.4s → 1.5s）」
                //      看起来一直在生效，其实对**代码速度**是空转 —— 真正让冷启动变快的是
                //      quicken。于是纯 Java 的 opus（Concentus）一直在解释/JIT 下跑，
                //      simpleperf 实测吃掉 **全机 21% 的 CPU**（除 GPU 驱动外最大的一项），
                //      通话中整机只剩 14~37% 空闲，Live2D 从 50fps 掉到 30~38fps ——
                //      表现就是靖哥哥说的「动画感觉不怎么连贯」。
                //      （排错时注意：改画布分辨率/抗锯齿/物理**全都不会动这个帧率**，
                //        因为瓶颈根本不在渲染。见 dist/live2d-poc/app.js 的 PHYS_EVERY 注释。）
                create("perf") {
                    // 先继承 debug 的全部设置（包名后缀、不混淆、逐版本号…），再改掉
                    // debuggable。⚠️ initWith 之后才能覆盖，顺序反了会被继承值盖回去。
                    initWith(getByName("debug"))
                    isDebuggable = false
                    // 沿用 debug 的签名（~/.android/debug.keystore）—— 换了签名就没法
                    // 覆盖安装到已装好的平板上，会退化成「装出第二个 App、数据全空」。
                    signingConfig = signingConfigs.getByName("debug")
                    // library 模块只有 debug / release 两种变体，app 这边多出一个 perf，
                    // 得告诉 AGP 拿哪个去配（不写会构建失败：variant 'perf' not found）。
                    //
                    // ⚠️⚠️ 必须 fallback 到 **debug**，绝不能把 release 排在前面 ——
                    //    2026-10-10 第一次真编 perf 档时踩到（`--dry-run` 查不出来，因为
                    //    那只是配置阶段，文件是否存在要执行到任务才校验）：
                    //      AndroidLibraryConventionPlugin 的 defaultConfig 里写了
                    //      `consumerProguardFiles("consumer-rules.pro")` → **release 变体**
                    //      会跑 `mergeReleaseConsumerProguardFiles` 并校验该文件存在，
                    //      而 core-*/feature-* 里只有 feature-assistant 建了这个文件，
                    //      于是报：`Supplied consumer proguard configuration does not exist:
                    //      ...\core-ui\consumer-rules.pro`。
                    //      （debug 变体不跑这个校验，所以 assembleDebug 一直正常 ——
                    //        这也解释了为什么问题只在 perf 档才暴露。）
                    //    语义上也该配 debug：perf 本就是「debug 去掉 debuggable」，
                    //    两边同为不混淆（isMinifyEnabled = false），配 release 反而会
                    //    让 library 走一套自己并不打算用的混淆路径。
                    matchingFallbacks += listOf("debug")
                }

                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }

            packaging {
                resources {
                    excludes += setOf(
                        "/META-INF/{AL2.0,LGPL2.1}",
                        "/META-INF/DEPENDENCIES",
                        "kotlin/**",
                    )
                }
            }
        }
    }
}
