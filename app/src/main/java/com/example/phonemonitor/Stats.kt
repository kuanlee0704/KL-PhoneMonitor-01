package com.example.phonemonitor

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun fmtDur(ms: Long): String {
    val h = ms / 3600_000L; val m = (ms / 60_000L) % 60
    return if (h > 0) "${h} 時 ${m} 分" else "${m} 分"
}

fun fmtMb(v: Long) = if (v >= 1024) "%.1f GB".format(v / 1024.0) else "$v MB"

fun fmtBytes(b: Long): String = when {
    b < 0 -> "—"
    b < 1_048_576 -> "${b / 1024} KB"
    b < 1_073_741_824L -> "%.1f MB".format(b / 1_048_576.0)
    else -> "%.2f GB".format(b / 1_073_741_824.0)
}

class Trend(
    val coveredMs: Long, val chargeMs: Long, val dischargeMs: Long, val screenMs: Long,
    val drain: Int, val charged: Int, val drainPerHour: Float,
    val tMin: Float, val tAvg: Float, val tMax: Float,
    val pDis: Float, val pChg: Float,
    val rx: Long, val tx: Long, val memMin: Int, val memUsedAvgPct: Int,
    val barTitle: String, val barLabels: List<String>, val barValues: List<Float>
)

object Stats {
    fun compute(rows: List<Sample>, now: Long, hours: Long): Trend {
        var cov = 0L; var chg = 0L; var dis = 0L; var scr = 0L
        var drain = 0; var charged = 0; var disNoChg = 0L; var rx = 0L; var tx = 0L

        // 長條圖分桶:1h→5分 / 6h→30分 / 24h→1小時 / 7天→1天
        val (size, nb, name) = when {
            hours <= 1L -> Triple(300_000L, 12, "每 5 分鐘耗電量")
            hours <= 6L -> Triple(1_800_000L, 12, "每 30 分鐘耗電量")
            hours <= 24L -> Triple(3_600_000L, 24, "每小時耗電量")
            else -> Triple(86_400_000L, 7, "每日耗電量")
        }
        val tz = TimeZone.getDefault().getOffset(now)
        val end = ((now + tz) / size) * size - tz
        val start = end - (nb - 1) * size
        val bars = FloatArray(nb)

        for (k in 1 until rows.size) {
            val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
            if (dt <= 0 || dt > 65 * 60_000L) continue
            cov += dt
            if (a.charging) chg += dt else dis += dt
            if (a.screenOn) scr += dt
            if (a.level >= 0 && b.level >= 0) {
                val dl = b.level - a.level
                if (!a.charging && !b.charging) {
                    disNoChg += dt
                    if (dl < 0) {
                        drain += -dl
                        if (b.ts >= start) {
                            val idx = ((b.ts - start) / size).toInt()
                            if (idx in 0 until nb) bars[idx] += (-dl).toFloat()
                        }
                    }
                } else if (a.charging && b.charging && dl > 0) charged += dl
            }
            if (a.rxBytes >= 0 && b.rxBytes >= a.rxBytes) rx += b.rxBytes - a.rxBytes
            if (a.txBytes >= 0 && b.txBytes >= a.txBytes) tx += b.txBytes - a.txBytes
        }

        val rate = if (disNoChg >= 600_000L) drain / (disNoChg / 3_600_000f) else -1f

        val temps = rows.map { it.tempC }.filter { it > -50f }
        var pd = 0f; var nd = 0; var pc = 0f; var nc = 0
        for (s in rows) {
            if (s.currentMa == Int.MIN_VALUE || s.voltageMv <= 0) continue
            val w = Math.abs(s.currentMa.toLong() * s.voltageMv) / 1_000_000f
            if (s.charging) { pc += w; nc++ } else { pd += w; nd++ }
        }
        val mem = rows.filter { it.memTotalMb > 0 }

        val fmtStr = when { size < 3_600_000L -> "HH:mm"; size == 3_600_000L -> "HH"; else -> "M/d" }
        val sdf = SimpleDateFormat(fmtStr, Locale.getDefault())
        val labels = (0 until nb).map { sdf.format(Date(start + it * size)) }

        return Trend(
            cov, chg, dis, scr, drain, charged, rate,
            if (temps.isEmpty()) 0f else temps.min(), if (temps.isEmpty()) 0f else temps.average().toFloat(),
            if (temps.isEmpty()) 0f else temps.max(),
            if (nd > 0) pd / nd else -1f, if (nc > 0) pc / nc else -1f,
            rx, tx, mem.minOfOrNull { it.memAvailMb } ?: 0,
            if (mem.isEmpty()) 0 else mem.map { (it.memTotalMb - it.memAvailMb) * 100 / it.memTotalMb }.average().toInt(),
            name, labels, bars.toList()
        )
    }
}
