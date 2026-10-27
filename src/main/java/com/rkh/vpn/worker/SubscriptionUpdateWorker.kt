package com.rkh.vpn.worker
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
class SubscriptionUpdateWorker(ctx:Context,p:WorkerParameters): CoroutineWorker(ctx,p){ override suspend fun doWork():Result=Result.success() }
