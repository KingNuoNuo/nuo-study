package com.nuostudy.core.sync

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

/**
 * 两个 App 的 Application 类都要实现这个接口，否则 `@HiltWorker` 拿不到依赖，
 * Worker 会在运行时报 "Could not instantiate worker"（编译器不会提醒你）。
 *
 * 用法：
 * ```
 * @HiltAndroidApp
 * class StudentApp : Application(), Configuration.Provider {
 *     @Inject lateinit var workerFactory: HiltWorkerFactory
 *     override val workManagerConfiguration: Configuration
 *         get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
 * }
 * ```
 */
typealias HiltWorkManagerConfig = Configuration.Provider
