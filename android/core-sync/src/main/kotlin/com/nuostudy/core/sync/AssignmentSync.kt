package com.nuostudy.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nuostudy.core.data.repo.AssignmentRepository
import com.nuostudy.core.network.ApiException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「已发布的作业单」从服务端拉到本地。
 *
 * 这是 M1 阶段 core-sync 唯一的任务 —— 但已经足够说明这个模块存在的理由：
 * 孩子端**读的一律是本地库**，所以必须在有网的时候悄悄把它喂饱，
 * 等孩子真的坐下来做题时（可能已经断网了），打开就能看到今天要做什么。
 *
 * ⚠️ 重试策略是这里最关键的一行代码：必须区分「该重试的错误」和「重试没用的错误」。
 *    网络超时 → 重试有意义；
 *    家庭令牌失效（401001）→ 重试一万次也还是失效，只会白耗电。
 *    不区分的话，家长改完令牌前，平板会一直后台转圈。
 */
@HiltWorker
class AssignmentSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: AssignmentRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // ⚠️ 不传日期 = 拉「最近若干条」，而不是「只拉今天」。
        //    孩子端首页的规则是「有今天的就显示今天，没有就显示最近一次」，
        //    只拉今天的话本地在周末会是空的 —— 退无可退，而那恰恰是最需要它的场景。
        return repo.refresh().fold(
            onSuccess = {
                repo.purgeOld()
                Result.success()
            },
            onFailure = { e ->
                when {
                    // 令牌 / 配置问题：重试无用，直接失败，等用户改配置后手动触发
                    e is ApiException && e.isAuthFailure -> Result.failure()
                    // 网络抖动：指数退避重试，但别无限重试
                    runAttemptCount < MAX_ATTEMPTS -> Result.retry()
                    else -> Result.failure()
                }
            },
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * 周期性后台同步。
     *
     * 用 KEEP 而不是 REPLACE：孩子端每次冷启动都会调这个方法，
     * 用 REPLACE 会把已经排好的任务不断重置，导致**它永远等不到执行窗口**
     * （WorkManager 的周期任务要等一个完整的间隔才会跑第一次）。
     */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<AssignmentSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /** 前台触发一次（如孩子端下拉刷新、家长端刚发布完作业）。 */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<AssignmentSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request,
        )
    }

    companion object {
        private const val UNIQUE_PERIODIC = "assignment-sync-periodic"
        private const val UNIQUE_NOW = "assignment-sync-now"
    }
}
