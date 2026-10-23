// 学情报告 · 家长端（手机竖屏）。
//
// ⚠️ 依赖的 `/reports/weekly`、`/wrong-book`、`/knowledge-points/tree` 三个接口
//    属于 M2（作答 / 批改 / 学情），**当前已整体暂缓** —— 服务端没有注册这些路由。
//    所以本模块现在只能渲染「作业完成情况」这块：孩子做没做、哪天做的。
//    「正确率 / 掌握度 / 错题本」要等服务端把 M2 接回来（实现已保留在 server/study.py）。
//
// 即便如此，这个模块也值得先建：家长端真正每天会打开的页面就是它。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jinyu.study.feature.report"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    // 学情数据来自 /api/v1/english/progress（EnglishApi）—— 与网页、孩子平板同一个接口。
    implementation(project(":core-network"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
