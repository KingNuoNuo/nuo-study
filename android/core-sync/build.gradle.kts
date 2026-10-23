// 同步层：离线优先的核心。
//
// ⚠️ 这里承载的是本项目**最重要的一条不变式**：
//    孩子端本地库是主库，提交按钮的语义是「已存到本地」，不是「已上传」。
//    所以作业提交一定要先把数据落 Room 的 outbox 表，再由 WorkManager 慢慢补传，
//    网络恢复后按 clientReqId 幂等重放 —— 服务端认这个键，重复提交返回同一条记录。
//
// 约束队列的两条硬要求（缺了会出事故）：
//   1. 有**重试上限**：不然一张坏图/一条脏数据会无限重试；
//   2. 有**退避策略**：不然孩子攒了 200 条离线记录一联网，会把家里的服务端打满。
plugins {
    id("app.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.core.sync"
}

dependencies {
    api(project(":core-data"))
    // LiveSync（SSE 实时同步）直接用 OkHttp 挂长连接，地址/令牌复用 ServerEndpoint
    implementation(project(":core-network"))
    implementation(libs.okhttp)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
