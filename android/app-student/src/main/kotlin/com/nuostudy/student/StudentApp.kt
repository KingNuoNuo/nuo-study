package com.nuostudy.student

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 孩子端 Application。
 *
 * ⚠️ 必须实现 [Configuration.Provider] 并提供 HiltWorkerFactory，
 *    否则 core-sync 里用 `@HiltWorker` 标注的 Worker 会在运行时
 *    抛 "Could not instantiate worker"（**编译期完全不报错**）。
 */
@HiltAndroidApp
class StudentApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // ⚠️ 必须写成**函数** `getWorkManagerConfiguration()`，不能写成属性
    //    `override val workManagerConfiguration`。老版本是 Java 接口，Kotlin 会把
    //    getter 映射成属性所以能过；WorkManager 2.12 起接口声明为
    //    `fun getWorkManagerConfiguration(): Configuration`，写属性会报
    //      "'workManagerConfiguration' overrides nothing" +
    //      "Class ... does not implement abstract member fun getWorkManagerConfiguration()"
    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
