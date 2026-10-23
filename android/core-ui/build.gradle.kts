// 设计系统：颜色 / 字体 / 间距 token，以及跨两个 App 复用的通用组件。
//
// ⚠️ 孩子端与家长端的视觉必须**同源但不同调**：孩子端要大字、高对比、可点区域大；
//    家长端信息密度高。所以这里只放「两端一致的 token 与基础组件」，
//    形态差异（字号档位、栏宽）由各自的 Theme 覆盖，不要在这里写死。
plugins {
    id("app.android.library")
    id("app.android.compose")
}

android {
    namespace = "com.nuostudy.core.ui"
}

dependencies {
    // 用 api 而非 implementation：下游 feature 模块写 Compose 代码时会直接用
    // Material3 / ui 里的类型，BOM 必须一并传递下去，否则每个模块都得再声明一遍。
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.test.manifest)
}
