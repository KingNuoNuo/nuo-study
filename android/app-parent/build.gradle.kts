// =============================================================================
// 家长端 · 手机竖屏
// =============================================================================
// 形态：手机，单手持握，单列布局。
// 定位：极简三动作 —— **拍照 → 确认 → 看报告**。不要在这里堆功能。
//
// 与孩子端的关键差异：
//  - 需要相机/相册权限（拍老师留的作业）
//  - 不做横屏适配，竖屏锁死
//  - 可以随时更新（不承担「孩子正在做题」的稳定性约束）
//  - versionCode 独立递增
plugins {
    id("app.android.application")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.parent"

    defaultConfig {
        applicationId = "com.jinyu.study.parent"
        // ⚠️ 与孩子端各自独立递增
        versionCode = 1
        versionName = "0.1.0"
        multiDexEnabled = true
    }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))

    implementation(project(":feature-capture"))
    implementation(project(":feature-report"))
    // 实时画面：家长端看孩子平板此刻的界面（对应孩子端 app-student 里的 mirror 抓帧）。
    implementation(project(":feature-mirror"))
    // 服务端设置页。与孩子端共用同一份实现（含「两步验证 + 异常翻成人话」的逻辑），
    // 版式用 ServerSettingsScreen(stackFields = true) 切成手机竖屏的堆叠式。
    implementation(project(":feature-settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager × Hilt —— ParentApp 同样要注入 HiltWorkerFactory。
    // 详见 app-student 里同一处的注释（缺了会在 KSP 阶段报 HiltWorkerFactory 无法解析）。
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
