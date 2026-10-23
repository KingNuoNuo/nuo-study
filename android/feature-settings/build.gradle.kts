// 服务端设置（地址 + 家庭令牌 + 测试连接）。孩子端与家长端共用。
//
// 为什么单独抽一个模块，而不是两个 App 各写一份：
//   这段代码里真正值钱的**不是版式，是「两步验证 + 异常翻译成人话」那套逻辑** ——
//   先 ping 验地址（免鉴权）、再 health 验令牌（Bearer），以及把
//   `ConnectException` 翻成「连不上，确认服务在跑、端口对」。
//   两边各写一份的话，早晚会漂移：孩子端补了新的错误分支，家长端没补，
//   家长就只会看到一行英文异常类名（这正是设置页最不该出现的体验）。
//
//   版式差异（平板横屏两框并排 / 手机竖屏必须堆叠）用参数表达，不外扩成两份实现。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.feature.settings"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    // StudyApi 虽然在 core-data 里是 api 透传的，但按本项目自己的规矩：
    // 凡是在本模块源码里**直接 import** 的 API，都要自己声明依赖，
    // 不靠别人传递（hilt-work 那次的教训）。
    implementation(project(":core-network"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
