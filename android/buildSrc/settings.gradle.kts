// buildSrc 自身的构建配置。
// 关键一步：把上级目录的 version catalog 引进来，让 buildSrc 的代码也能用 libs.xxx，
// 避免「gradle/libs.versions.toml 和 buildSrc 各写一份版本号」这种必然漂移的重复。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
