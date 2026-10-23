// 数据层：孩子端 Room 缓存库 + 服务端地址等配置的 DataStore + 仓储实现。
//
// ⚠️ 再强调一次（这是本项目最容易搞错的地方）：
//    本模块里的 Room 库是**孩子端自己的缓存库**，只存 6 张表
//    （local_assignment / local_item / answer_outbox / local_media / local_kp / local_practice），
//    **不是**服务端那 18 张表。两套 SQLite 职责完全不同，别照抄服务端 schema。
//    详见 docs/client-design.md 第 3 节。
import java.util.Properties

plugins {
    id("app.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/*
 * 服务端地址与家庭令牌：**从 local.properties 读，绝不进仓库**。
 *
 * 早先这两个值是写死在 ServerDefaults.kt 里的常量（自家 NAS 的 IP + 默认令牌），
 * 好处是装完就能用，坏处是一公开就带着「我家服务器的地址」。现在改成：
 *   · 仓库里没有值（local.properties 在 .gitignore 里）；
 *   · 本机在 android/local.properties 里填一次，编译时注入 BuildConfig；
 *   · 没填也能编、能跑，只是 App 启动后要求家长在「设置」里手输。
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val nuoBaseUrl: String = localProps.getProperty("nuo.baseUrl") ?: ""
val nuoFamilyToken: String = localProps.getProperty("nuo.familyToken") ?: ""

android {
    namespace = "com.jinyu.study.core.data"

    buildFeatures { buildConfig = true }

    defaultConfig {
        // 值里可能带引号（虽然正常不会有），转义一下免得 Kotlin 字符串编不过
        buildConfigField("String", "NUO_BASE_URL", "\"${nuoBaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "NUO_FAMILY_TOKEN", "\"${nuoFamilyToken.replace("\"", "\\\"")}\"")
    }
}

dependencies {
    api(project(":core-network"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room 的迁移测试跑在真机上，用 androidTest 目录；本地单测只测纯逻辑
    testImplementation(libs.junit)
}
