// =============================================================================
// 小学学习 App · 客户端（孩子端平板横屏 + 家长端手机竖屏）
//
// 这是**两个独立的 application module**，不是一个 App 装两种角色：
//   :app-student  孩子端 —— 横屏、作答台、权限尽量少（不要相机/通讯录）
//   :app-parent   家长端 —— 竖屏、拍照上传、确认作业、看报告
// 两者共享 :core-*，但不共享启动入口，更新节奏也各自独立
// （孩子正在做题时，绝不能因为家长端点了个更新就把 App 重启）。
// =============================================================================

pluginManagement {
    repositories {
        // 国内网络：阿里云镜像放最前（实测响应 0.1s 级），官方源留作兜底 ——
        // 镜像同步有延迟时，缺失的 artifact 会自然回落到 google()/mavenCentral()。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "primary-study"

include(
    // 应用层：两个孩子各自独立安装
    ":app-student",
    ":app-parent",

    // 基础能力层
    ":core-ui",          // 设计系统、通用组件、主题
    ":core-network",     // Retrofit + OkHttp + kotlinx.serialization
    ":core-data",        // Room 缓存库 + DataStore 配置 + 仓储层
    ":core-sync",        // WorkManager 同步队列（离线补传核心）

    // 业务功能层
    ":feature-homework", // 今日作业：作答台
    ":feature-capture",  // 截图上传 + 识别确认（家长端）
    ":feature-settings", // 服务端设置 —— 两端共用（孩子的平板和孩子妈的手机各自要配一次）
    ":feature-learning", // 学习板块容器：三科入口 + 自主练习
    ":feature-chinese",
    ":feature-math",
    ":feature-english",
    ":feature-report",   // 学情报告（家长端）
    ":feature-mirror",   // 实时画面：家长端看孩子平板此刻的界面
    ":feature-assistant",// 小智 AI 悬浮助手（孩子端）：右下角小姐姐 + 语音对话
)
