// 作业录入 · 家长端（手机竖屏）：拍照/选图 → 上传 → 等识别 → 逐项确认 → 发布。
//
// ⚠️ 本模块的核心不是「上传」，而是**确认界面**。
//    识别结果永远是草稿，家长不点确认，孩子端就看不到 ——
//    这是防错，不是合规（识别错了孩子就照着错的做）。
//    所以界面上「模型不确定的字段」要高亮，让家长重点核对。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.feature.capture"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 相机 / 相册选图
    implementation(libs.androidx.activity.compose)

    // MultipartBody / RequestBody —— 上传作业原图要自己拼 multipart。
    // 虽然是 core-network 的间接依赖，但本模块**直接 import**，所以自己声明
    // （hilt-work 那次的教训：靠传递引用会以「运行期找不到类」的形式爆出来）。
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
