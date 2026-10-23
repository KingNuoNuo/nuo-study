package com.nuostudy.core.data.prefs

import com.nuostudy.core.data.BuildConfig

/**
 * **内置的默认配置** —— 让 App 装完就能直接连上，不用每次手输地址和令牌。
 *
 * ## ⚠️ 值从哪来：`android/local.properties`，**不在仓库里**
 *
 * 这两个值曾经是写死的常量（自家 NAS 的 IP + 服务端默认令牌）。好处是装完就能用，
 * 坏处是一公开仓库就带着「我家服务器的地址」—— 所以挪到了构建期注入：
 *
 * ```properties
 * # android/local.properties（已被 .gitignore 挡住，每台机器一份）
 * nuo.baseUrl=http://192.168.1.10:8092/
 * nuo.familyToken=<服务端 .env 里的 FAMILY_TOKEN>
 * ```
 *
 * 没填也能编译、能运行，只是 [ENABLED] 为 false，家长必须在「设置」页手输一次。
 *
 * ## 生效规则（见 [ServerConfigStore.load] / [ServerConfigStore.update]）
 *
 * 1. DataStore 里**该项为空**时回落到这里；已经存过值的一律以存储为准。
 * 2. 家长填的值**如果和内置值一模一样，不会被存进去**（视为「没填」）。
 *    所以改了 local.properties、重新编译安装即生效，不会被设备上的历史值悄悄压住。
 *    于是：
 *      · 家长在设置页填了**不同**的地址并保存 → 存的值生效，内置值被忽略；
 *      · 把输入框清空再保存 → 那一项退回内置值（等价于「恢复默认」）；
 *      · 直接点「测试连接」（它内部会先保存）→ 不改变任何存储，内置值仍然生效。
 *
 * 注意内置值在**编译期**烧进 APK，改了必须重新编译安装才生效 —— 这类问题
 * 在真机上表现为「明明改了配置/服务端，App 还在连老地址」，很费时间。
 */
object ServerDefaults {

    /**
     * 家庭服务端地址，形如 `http://192.168.1.10:8092/`。
     *
     * 必须**写完整形式**（带 `http://` 与结尾斜杠）：这个值会绕过设置页的输入框
     * 直接进入 [ServerConfigStore] 与 OkHttp 的 `baseUrl`，不经过
     * [ServerConfigStore.normalizeBaseUrl] 的补全。少了结尾斜杠时，Retrofit 拼路径
     * 会吃掉最后一段。
     */
    val BASE_URL: String = BuildConfig.NUO_BASE_URL.trim()

    /** 家庭令牌，对应服务端 `.env` 的 `FAMILY_TOKEN`。 */
    val FAMILY_TOKEN: String = BuildConfig.NUO_FAMILY_TOKEN.trim()

    /**
     * 有没有可用的内置值。两项都填了才算 —— 只填地址不填令牌等于没配（会一直 401）。
     */
    val ENABLED: Boolean
        get() = BASE_URL.isNotBlank() && FAMILY_TOKEN.isNotBlank()

    /**
     * 学生端「实时画面」镜像的**出厂默认**：开着。
     *
     * 目的是让家长（或我）在电脑/手机上打开
     * `http://<服务端>/debug/screen?token=<FAMILY_TOKEN>`
     * 就能看到孩子平板此刻的界面，不用凑过去看，也不用让孩子念。
     *
     * ⚠️ 这是**调试功能**，孩子端设置页里可以关（设置页有家长口令挡着）。
     *    它只在 App 处于前台时抓屏，每 3 秒一帧、约 40 KB，
     *    走的是家里的局域网，不上传任何第三方。
     *    孩子端那个 App 一旦被卸载或换了正式令牌，服务端那组接口也就打不通了。
     *
     * 想彻底关掉：把这里改成 false，并在服务端删掉 `api/v1/debug/` 前缀下的那几个路由。
     */
    const val MIRROR_ENABLED = true
}
