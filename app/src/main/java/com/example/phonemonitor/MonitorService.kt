package com.example.phonemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

class MonitorService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler

    private val tick = object : Runnable {
        override fun run() {
            doSample()
            h.postDelayed(this, Prefs.interval(this@MonitorService) * 1000L)
        }
    }

    // 插拔充電器時立刻多記一筆
    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { h.post { doSample() } }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("monitor").also { it.start() }
        h = Handler(thread.looper)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("mon", "背景監測", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotif("監測啟動中…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(1, n)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rcv, f)

        h.post(tick)
    }

    private fun doSample() {
        try {
            val s = Collector.read(this)
            val db = Db.get(this)
            db.insert(s)
            if (s.ts % 20 == 0L) db.trim(30)
            val cur = if (s.currentMa == Int.MIN_VALUE) "—" else "${s.currentMa} mA"
            getSystemService(NotificationManager::class.java).notify(
                1, buildNotif("電量 ${s.level}%  ${"%.1f".format(s.tempC)}°C  $cur")
            )
        } catch (e: Exception) { /* 忽略單次失敗 */ }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "mon")
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("手機監測中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { unregisterReceiver(rcv) } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }
}
