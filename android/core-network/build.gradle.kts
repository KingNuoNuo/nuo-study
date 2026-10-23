// 网络层：Retrofit + OkHttp + kotlinx.serialization。
//
// 本项目**只有一个外部网络目标**：自家的家庭服务器（局域网或内网穿透地址）。
// 所以这里要做的事比一般 App 少得多 —— 没有多 BaseUrl、没有 CDN、没有第三方 SDK。
//
// ⚠️ BaseUrl 是运行时由用户在家长端填写的（见 core-data 的 ServerConfig），
//    因此 Retrofit 实例必须能重建，不能做成 init 里写死的单例。
plugins {
    id("app.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.core.network"

    buildFeatures {
        buildConfig = true   // 需要 DEBUG 标志决定是否挂日志拦截器
    }
}

dependencies {
    // api：Retrofit 的注解、Response 类型会出现在上层（core-data）的公开签名里
    api(libs.retrofit)
    api(libs.retrofit.kotlinx.serialization)
    api(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
