package com.example.phonemonitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.io.File

object Collector {

    private inline fun <T> safe(def: T, f: () -> T): T = try { f() } catch (e: Throwable) { def }

    private fun sys(name: String): Int = safe(-1) {
        var v = File("/sys/class/power_supply/battery/$name").readText().trim().toInt()
        if (v in 1..99999) v *= 1000   // 部分機型單位是 mAh,統一轉成 µAh
        v
    }

    private var designCache = -2
    private fun designFromProfile(ctx: Context): Int {
        if (designCache != -2) return designCache
        designCache = safe(-1) {
            val c = Class.forName("com.android.internal.os.PowerProfile")
            val o = c.getConstructor(Context::class.java).newInstance(ctx)
            val mah = c.getMethod("getBatteryCapacity").invoke(o) as Double
            if (mah > 500) (mah * 1000).toInt() else -1
        }
        return designCache
    }

    /** 每一項資料各自容錯:某項讀不到只會變成「未知」,不會讓整筆紀錄失敗 */
    fun read(ctx: Context): Sample {
        val i = safe<Intent?>(null) { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10f
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val cycle = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1 else -1

        val bm = safe<BatteryManager?>(null) { ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager }
        val curRaw = safe(Int.MIN_VALUE) { bm!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
        val currentMa = when {
            curRaw == Int.MIN_VALUE -> Int.MIN_VALUE
            curRaw == 0 -> 0
            Math.abs(curRaw) >= 10000 -> curRaw / 1000   // µA -> mA
            else -> curRaw                                // 少數機型直接回報 mA
        }
        val chg = safe(-1) { bm!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).let { if (it == Int.MIN_VALUE) -1 else it } }
        val soh = if (Build.VERSION.SDK_INT >= 34) safe(-1) { bm!!.getIntProperty(10).let { if (it == Int.MIN_VALUE) -1 else it } } else -1

        val mi = ActivityManager.MemoryInfo()
        safe(Unit) { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi) }

        var stFree = -1L; var stTotal = -1L
        safe(Unit) {
            val st = StatFs(Environment.getDataDirectory().path)
            stFree = st.availableBytes / 1048576; stTotal = st.totalBytes / 1048576
        }

        val net = safe("未知") {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val an = cm.activeNetwork
            val caps = if (an == null) null else cm.getNetworkCapabilities(an)
            when {
                caps == null -> "無網路"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "行動網路"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "乙太網路"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "藍牙"
                else -> "其他"
            }
        }

        var thermal = -1; var screen = true; var saver = false
        safe(Unit) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 29) thermal = pm.currentThermalStatus
            screen = pm.isInteractive; saver = pm.isPowerSaveMode
        }

        val sysDesign = sys("charge_full_design")
        val design = if (sysDesign > 0) sysDesign else designFromProfile(ctx)

        return Sample(
            System.currentTimeMillis(), level, status, plugged, health, temp, volt, currentMa,
            chg, sys("charge_full"), design, cycle, soh,
            (mi.availMem / 1048576).toInt(), (mi.totalMem / 1048576).toInt(),
            stFree, stTotal,
            safe(-1L) { TrafficStats.getTotalRxBytes() }, safe(-1L) { TrafficStats.getTotalTxBytes() }, net,
            thermal, screen, saver,
            safe(-1L) { TrafficStats.getMobileRxBytes() }, safe(-1L) { TrafficStats.getMobileTxBytes() }
        )
    }
}

/** 讀取 + 寫入資料庫,並把成功/失敗記錄下來供「診斷」顯示 */
object Recorder {
    fun record(ctx: Context): Sample? = try {
        val s = Collector.read(ctx)
        Db.get(ctx).insert(s)
        Prefs.setOk(ctx, s.ts)
        s
    } catch (e: Throwable) {
        Prefs.setErr(ctx, "${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
