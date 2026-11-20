package com.radium.inkwell.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 自动校验的调度。
 *
 * 每天一次轻量校验（只测站点存活），失效的按分级规则自动移除。
 * 完整校验（搜索 -> 详情 -> 正文）留给手动与每 7 天一轮的分批任务 ——
 * 515 个来源跑一轮一个多小时，WorkManager 单次跑不完。
 */
object SourceCheckScheduler {

    private const val WORK_NAME = "source-daily-light-check"

    /** @param wifiOnly 只在 Wi-Fi 下跑 */
    fun enable(context: Context, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<SourceCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInputData(workDataOf(SourceCheckWorker.KEY_LIGHT to true))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // ---- 半月完整校验 ----

    private const val FULL_WORK_NAME = "source-full-check"

    fun enableFull(context: Context, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val req = PeriodicWorkRequestBuilder<SourceFullCheckWorker>(15, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            FULL_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun disableFull(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FULL_WORK_NAME)
    }
}
