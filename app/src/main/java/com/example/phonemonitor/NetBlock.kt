package com.example.phonemonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/** 以本機 VPN 把「被選取的 App」的所有網路封包丟進黑洞 => 該 App 無法上網(含背景) */
class NetBlockService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var th: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        stopVpn()
        val pkgs = Prefs.netBlocked(this)
        if (pkgs.isEmpty()) { stopSelf(); return }
        try {
            val b = Builder().setSession("手機監測 · 網路封鎖")
                .addAddress("10.77.0.2", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd00:77::2", 128).addRoute("::", 0)
            var ok = 0
            for (p in pkgs) try { b.addAllowedApplication(p); ok++ } catch (_: Exception) {}
            if (ok == 0) { stopSelf(); return }   // 沒有任何有效 App:不建立,避免把全部流量都擋掉
            pfd = b.establish()
            val fd = pfd ?: return
            th = Thread {
                val ins = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32767)
                try { while (!Thread.currentThread().isInterrupted) { if (ins.read(buf) < 0) break } } catch (_: Exception) {}
            }.also { it.start() }
        } catch (e: Throwable) { Prefs.setErr(this, "vpn: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun stopVpn() {
        th?.interrupt(); th = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    override fun onRevoke() { Prefs.setNetBlockOn(this, false); stopVpn(); stopSelf() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }
}

object NetBlock {
    const val REQ = 200

    /** 需要時會跳出系統的 VPN 授權視窗 */
    fun enable(act: Activity) {
        if (Prefs.netBlocked(act).isEmpty()) return
        val i = VpnService.prepare(act)
        if (i != null) act.startActivityForResult(i, REQ) else start(act)
    }

    fun start(ctx: Context) {
        Prefs.setNetBlockOn(ctx, true)
        ctx.startService(Intent(ctx, NetBlockService::class.java))
    }

    fun stop(ctx: Context) {
        Prefs.setNetBlockOn(ctx, false)
        ctx.stopService(Intent(ctx, NetBlockService::class.java))
    }

    /** 清單變更後重新套用 */
    fun refresh(ctx: Context) {
        if (!Prefs.netBlockOn(ctx)) return
        if (Prefs.netBlocked(ctx).isEmpty()) stop(ctx) else ctx.startService(Intent(ctx, NetBlockService::class.java))
    }
}
