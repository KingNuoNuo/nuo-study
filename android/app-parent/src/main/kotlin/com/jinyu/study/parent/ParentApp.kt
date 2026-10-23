package com.jinyu.study.parent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ParentApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * 家长端同样要提供 WorkerFactory —— 上传/识别进度轮询走 WorkManager，
     * 这样家长切到后台再接回来，进度不会丢。
     *
     * ⚠️ 必须是函数形式 `getWorkManagerConfiguration()`：WorkManager 2.12 起接口
     *    声明为 `fun getWorkManagerConfiguration(): Configuration`，写成属性会报
     *    "'workManagerConfiguration' overrides nothing"（详见 StudentApp 里同一处注释）。
     */
    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
