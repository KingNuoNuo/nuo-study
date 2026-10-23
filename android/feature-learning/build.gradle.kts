// 学习板块容器 · 孩子端：三科入口 + 自主练习。
//
// 与「今日作业」的关系（这是导航设计的关键，别搞混）：
//   今日作业 = 老师布置的 → 依赖家长拍照识别 → 有截止、要提交
//   学习     = 孩子自己练的 → **完全不依赖家长**，打开就能练
//
// ⚠️ 导航上，学习板块内部**不要用横向 Pager**。外层已经有一个横向 Pager
//    在切「作业 / 学习」，再嵌一层会抢手势，要写复杂的嵌套滚动仲裁，不值当。
//    学习内部用顶部 Tab 点击切换即可。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.feature.learning"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))

    implementation(project(":feature-chinese"))
    implementation(project(":feature-math"))
    implementation(project(":feature-english"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
