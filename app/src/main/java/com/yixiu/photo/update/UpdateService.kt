package com.yixiu.photo.update

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 下载期间的常驻前台服务：Android 14 起，应用退到后台进入 cached 状态后网络会被掐断，
 * 前台服务能让下载继续跑完，它的通知同时就是进度条。
 */
class UpdateService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = UpdateManager.progressNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(UpdateManager.NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(UpdateManager.NOTIFY_ID, n)
        }
        return START_NOT_STICKY
    }

    /** Android 15 对 dataSync 服务有时长限制：到点就结束，已下载部分留给下次续传。 */
    override fun onTimeout(startId: Int) {
        UpdateManager.cancelDownload()
        stopSelf()
    }
}
