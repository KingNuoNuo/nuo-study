// 今日作业 · 孩子端作答台（平板横屏）。
//
// 这是孩子端的**主战场**，也是全项目交互最容易出问题的地方：
//  - 手写画布与左右滑动手势会打架 → 进入作答态必须锁滑动（见 client-design.md）
//  - 必须自绘键盘 → 系统键盘的自动纠错与联想会替孩子拼出正确答案
//  - 每答一题先写本地 outbox，再做后台补传
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.feature.homework"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
