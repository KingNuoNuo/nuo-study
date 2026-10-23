// 实时画面 · 家长端（手机竖屏）：看孩子平板此刻的界面。
//
// ⚠️ 这个模块**只有家长端接**（:app-parent）。孩子端不引用它 ——
//    孩子端那边的对应物是 `app-student` 里的 `mirror` 包（负责**抓帧上传**），
//    一个上传、一个观看，代码分居两侧，靠服务端那组 `api/v1/debug/` 接口对接。
//    两边的类名都叫 Mirror*，所以看代码前先确认自己在哪个模块里。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.feature.mirror"
}

dependencies {
    implementation(project(":core-ui"))
    // StudyApi 与那几个调试端点。core-data 是 api 暴露 core-network 的，
    // 但本模块**直接 import** StudyApi / FrameInfoDto，按本项目规矩自己声明。
    implementation(project(":core-data"))
    implementation(project(":core-network"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // repeatOnLifecycle / LocalLifecycleOwner。前者在 -ktx 里，后者在 -compose 里，
    // 而 -compose **只传递 lifecycle-runtime**（不含 ktx）—— 按本项目规矩，
    // 直接 import 的 API 都要自己声明依赖，不靠传递。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // ResponseBody —— StudyApi.latestFrame() 的返回类型，本模块直接用它读字节流
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
