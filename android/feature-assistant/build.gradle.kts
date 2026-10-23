// 小智 AI 悬浮助手（孩子端）：右下角悬浮小姐姐 + 语音对话。
//
// 模块边界（刻意收得很窄）：
//   - 所有「小智」相关的身份/OTA/激活/WebSocket/opus/音频都在本模块内聚，
//     对外只暴露 [AssistantOverlay] 一个 Composable。
//   - 不依赖 core-network / core-data：小智是**外部云服务**（api.tenclass.net），
//     走家里的 OkHttp 单例（带 DynamicUrlInterceptor 改写 host）反而全错 ——
//     那个拦截器会把请求改写到局域网服务端。所以这里自建独立 client。
//   - 不持任何业务数据：识别、作业、报告一概不知。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.kotlin.serialization) // 小智协议 DTO（OtaResponse 等）@Serializable
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.feature.assistant"
}

dependencies {
    implementation(project(":core-ui"))

    // 通话按钮的听筒 / 挂断图标。Phone 在 icons-core、CallEnd 在 extended，
    // 按项目规矩「源码直接 import 的 API 自己声明，别靠 core-ui 的 api() 传递」。
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // WebSocket（小智下行 TTS / 上行语音）+ OTA/激活的 HTTP。
    // 本模块直接 import okhttp3.*，按项目规矩自己声明，不靠传递。
    implementation(libs.okhttp)

    // 小智协议 JSON（hello/listen/tts/stt...）用 kotlinx.serialization 的
    // JsonObject 构建/解析 —— 字段高度多态，用 @Serializable data class 反而别扭。
    implementation(libs.kotlinx.serialization.json)

    // opus 编解码（纯 Java，帧长精确可控）
    implementation(libs.concentus)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
