package com.example.phonemonitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.net.TrafficStats
import java.io.File

object Collector {

    private fun sys(name: String): Int = try {
        var v = File("/sys/class/power_supply/battery/$name").readText().trim().toInt()
        if (v in 1..99999) v *= 1000   // 部分機型單位是 mAh,統一轉成 µAh
        v
    } catch (e: Exception) { -1 }

    fun read(ctx: Context): Sample {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10f
        val volt = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val cycle = if (Build.VERSION.SDK_INT >= 34) i?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1 else -1

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val curRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = when {
            curRaw == Int.MIN_VALUE || curRaw == 0 -> if (curRaw == 0) 0 else Int.MIN_VALUE
            Math.abs(curRaw) >= 10000 -> curRaw / 1000   // µA -> mA
            else -> curRaw                                // 少數機型直接回報 mA
        }
        val chg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).let { if (it == Int.MIN_VALUE) -1 else it }
        val soh = if (Build.VERSION.SDK_INT >= 34) bm.getIntProperty(10).let { if (it == Int.MIN_VALUE) -1 else it } else -1

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val st = StatFs(Environment.getDataDirectory().path)

        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "無網路"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "行動網路"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "乙太網路"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "藍牙"
            else -> "其他"
        }

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1

        return Sample(
            System.currentTimeMillis(), level, status, plugged, health, temp, volt, currentMa,
            chg, sys("charge_full"), sys("charge_full_design"), cycle, soh,
            (mi.availMem / 1048576).toInt(), (mi.totalMem / 1048576).toInt(),
            st.availableBytes / 1048576, st.totalBytes / 1048576,
            TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), net,
            thermal, pm.isInteractive, pm.isPowerSaveMode
        )
    }
}
