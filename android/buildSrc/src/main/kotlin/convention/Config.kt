package convention

/**
 * 全局基线。**所有模块共享这一份**，改这里即全局生效。
 *
 * ⚠️ 版本选择理由（别随手改）：
 *  - MIN_SDK = 26：孩子端要装在家里可能已经服役多年的安卓平板上。Android 8.0
 *    覆盖了 2017 年之后的设备，同时 26 是自适应图标（adaptive icon）的下限，
 *    不必再为旧版本准备一整套 legacy mipmap。
 *  - COMPILE_SDK = 37：**由依赖倒逼，不是想追新**。2026 年 9 月那一批 AndroidX
 *    （core-ktx 1.19.1 / Compose 1.12.1 / lifecycle 2.11.0 / okhttp-android 5.5.0 …）
 *    共 19 个 artifact 的 AAR metadata 都写着 `minCompileSdk = 37`，跑
 *    `checkDebugAarMetadata` 时会一次性全部报出来。此时只能把 compileSdk 抬到 37，
 *    或者把这一整批依赖退回上一代 —— 前者是官方预期路径。
 *    另：compileSdk = 37 需要 AGP ≥ 9.1.1 + Gradle ≥ 9.3.1（当前 9.4.1 / 9.8.1 满足）。
 *  - JVM_TARGET = 17：AGP 9 的最低要求。⚠️ AGP 9 起 Kotlin 是内置的，
 *    jvmTarget 会自动跟随 `compileOptions.targetCompatibility`，不需要再单独设。
 */
object Config {
    const val COMPILE_SDK = 37

    /**
     * AGP 9 起 Android 平台开始带**小版本号**（android-37.0 / 37.1 / 37.2 …），
     * 所以 SDK 版本不能再用一个 Int 表达，要写成
     * `compileSdk { version = release(37) { minorApiLevel = 2 } }`。
     * 装包时对应的名字是 `platforms;android-37.2`。
     */
    const val COMPILE_SDK_MINOR = 2

    const val MIN_SDK = 26
    const val TARGET_SDK = 37
    const val JVM_TARGET = 17
}
