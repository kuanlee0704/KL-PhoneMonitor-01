package com.example.phonemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開機、以及 App 被覆蓋更新後,自動恢復監測 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            if (Prefs.netBlockOn(ctx) && android.net.VpnService.prepare(ctx) == null)
                ctx.startService(Intent(ctx, NetBlockService::class.java))
        } catch (_: Exception) {}
        if (Prefs.enabled(ctx)) {
            try { ctx.startForegroundService(Intent(ctx, MonitorService::class.java)) }
            catch (e: Exception) { Prefs.setErr(ctx, "boot: ${e.message}") }
            try { SampleJob.schedule(ctx) } catch (_: Exception) {}
        }
    }
}
