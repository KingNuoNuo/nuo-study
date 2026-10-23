// =============================================================================
// 孩子端 · 平板横屏
// =============================================================================
// 形态：10–12 寸平板，**锁定横屏**（在 AndroidManifest 里 screenOrientation）。
// 定位：作答台。三栏并排 —— 左「作业单」/ 中「作答区」/ 右「要求与进度」。
//
// 三条与家长端刻意不同的设定：
//  1. 权限尽量少：**不要相机**（孩子端不拍照上传），只在朗读题时按需申请录音；
//  2. versionCode 独立：孩子正在做题时绝不能因为家长端点了个更新就被打断；
//  3. 不做深色模式以外的主题变化，减少分心。
//
// 不依赖 :feature-capture 与 :feature-report —— 那两个是家长端的活。
plugins {
    id("app.android.application")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.student"

    defaultConfig {
        applicationId = "com.nuostudy.student"
        // ⚠️ 与家长端**各自独立**递增，不要同步
        versionCode = 1
        versionName = "0.1.0"

        // 单家庭部署，用户量固定，不需要多 dex 自动拆分以外的处理
        multiDexEnabled = true
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))

    implementation(project(":feature-homework"))
    implementation(project(":feature-learning"))
    implementation(project(":feature-chinese"))
    implementation(project(":feature-math"))
    implementation(project(":feature-english"))
    // 悬浮小智助手（右下角小姐姐 + 语音对话）
    implementation(project(":feature-assistant"))
    // 服务端设置页。与家长端共用同一份实现（含「两步验证 + 异常翻成人话」的逻辑），
    // 版式差异由 ServerSettingsScreen(stackFields = ...) 表达。
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // 「实时画面」镜像（mirror 包）直接用到的东西。
    // 按本项目的规矩：**凡在自己源码里直接 import 的 API，都要自己声明**，
    // 哪怕它是 core-data 的传递依赖 —— hilt-work 那次的教训（注解处理期才报错，
    // 看着像代码写错）就是这么来的。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // viewModelScope / hiltViewModel()。
    // ⚠️ 显式声明，不靠别人传递 —— 同一个坑本项目踩过：
    //    core-sync 里 hilt-work 是 implementation，不传递到这儿，
    //    结果 KSP 阶段才报 HiltWorkerFactory 找不到。
    //    凡是在自己源码里**直接 import 的** API，都要自己声明依赖。
    // 注：设置页实现已迁到 :feature-settings（那边自带这两个依赖）；
    //    这里保留是因为 HomeworkScreen 接下来要接自己的 VM。
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager × Hilt。StudentApp 要实现 Configuration.Provider 并注入
    // HiltWorkerFactory（core-sync 里 @HiltWorker 的 Worker 靠它拿依赖）。
    // ⚠️ 这两个**必须显式声明**：core-sync 里的 hilt-work 是 implementation，
    //    不会传递到这里；缺了会在 KSP 阶段报
    //      "InjectProcessingStep was unable to process 'workerFactory'
    //       because 'HiltWorkerFactory' could not be resolved"
    //    （是注解处理的报错，看着像代码写错，其实是依赖没配）
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
