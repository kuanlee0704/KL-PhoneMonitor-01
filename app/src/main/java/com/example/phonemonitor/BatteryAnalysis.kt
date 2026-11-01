package com.example.phonemonitor

import java.util.TimeZone

class Sess(
    val charging: Boolean, val start: Long, val end: Long, val lv0: Int, val lv1: Int,
    val avgW: Float, val maxT: Float, val screenMs: Long, val minLv: Int
)

class BatAnalysis(
    val days: Float, val sessions: List<Sess>,
    val rate: Float, val onRate: Float, val offRate: Float,
    val chargeRate: Float, val chargeRate80: Float, val avgChargeW: Float,
    val avgChargeTemp: Float, val maxChargeTemp: Float,
    val totalDrain: Int, val eqCycles: Float, val chargeCount: Int,
    val avgStartLv: Float, val avgEndLv: Float, val deepCount: Int,
    val fullPlugMs: Long, val chargeMs: Long,
    val hourDrain: FloatArray, val capPts: List<ChartView.Pt>
)

object BatteryAnalysis {
    fun compute(rows: List<Sample>): BatAnalysis? {
        if (rows.size < 2) return null
        val sessions = ArrayList<Sess>()
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j + 1 < rows.size && rows[j + 1].charging == rows[i].charging && rows[j + 1].ts - rows[j].ts <= 65 * 60_000L) j++
            if (j > i) {
                var pw = 0f; var pn = 0; var mt = -100f; var scr = 0L; var mn = 101
                for (k in i..j) {
                    val s = rows[k]
                    if (s.currentMa != Int.MIN_VALUE && s.voltageMv > 0) { pw += Math.abs(s.currentMa.toLong() * s.voltageMv) / 1_000_000f; pn++ }
                    if (s.tempC > mt) mt = s.tempC
                    if (s.level in 0 until mn) mn = s.level
                    if (k > i && rows[k - 1].screenOn) scr += rows[k].ts - rows[k - 1].ts
                }
                sessions.add(Sess(rows[i].charging, rows[i].ts, rows[j].ts, rows[i].level, rows[j].level,
                    if (pn > 0) pw / pn else -1f, mt, scr, mn))
            }
            i = j + 1
        }

        val tz = TimeZone.getDefault().getOffset(rows.last().ts)
        var onMs = 0L; var offMs = 0L; var onDrain = 0; var offDrain = 0
        var chMs = 0L; var fullMs = 0L; var rise = 0; var riseMs = 0L; var rise80 = 0; var ms80 = 0L
        var tSum = 0f; var tN = 0; var tMax = -100f
        val hour = FloatArray(24)

        for (k in 1 until rows.size) {
            val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
            if (dt <= 0 || dt > 65 * 60_000L || a.level < 0 || b.level < 0) continue
            if (!a.charging && !b.charging) {
                val dl = maxOf(0, a.level - b.level)
                if (a.screenOn) { onMs += dt; onDrain += dl } else { offMs += dt; offDrain += dl }
                hour[(((b.ts + tz) / 3_600_000L) % 24).toInt()] += dl.toFloat()
            } else if (a.charging && b.charging) {
                chMs += dt
                if (a.level >= 100) fullMs += dt
                if (b.level > a.level && a.level < 100) { rise += b.level - a.level; riseMs += dt }
                if (a.level < 80 && b.level <= 80) { rise80 += maxOf(0, b.level - a.level); ms80 += dt }
                if (a.tempC > -50f) { tSum += a.tempC; tN++; if (a.tempC > tMax) tMax = a.tempC }
            }
        }
        val totalDrain = onDrain + offDrain
        val allMs = onMs + offMs
        fun rate(d: Int, ms: Long) = if (ms >= 600_000L) d / (ms / 3_600_000f) else -1f
        val days = maxOf(1f, (rows.last().ts - rows.first().ts) / 86_400_000f)
        for (h in 0 until 24) hour[h] /= days

        val ch = sessions.filter { it.charging }
        val dis = sessions.filter { !it.charging }
        val chW = ch.filter { it.avgW >= 0 }.map { it.avgW }

        // 每日平均「推估容量」
        val capByDay = java.util.TreeMap<Long, MutableList<Float>>()
        for (s in rows) {
            if (s.level >= 30 && s.chargeUah > 0) {
                val d = ((s.ts + tz) / 86_400_000L) * 86_400_000L - tz
                capByDay.getOrPut(d) { ArrayList() }.add(s.chargeUah / 1000f * 100f / s.level)
            }
        }
        val cap = capByDay.map { ChartView.Pt(it.key, it.value.average().toFloat(), false) }

        return BatAnalysis(
            days, sessions,
            rate(totalDrain, allMs), rate(onDrain, onMs), rate(offDrain, offMs),
            if (riseMs >= 600_000L) rise / (riseMs / 3_600_000f) else -1f,
            if (ms80 >= 600_000L) rise80 / (ms80 / 3_600_000f) else -1f,
            if (chW.isNotEmpty()) chW.average().toFloat() else -1f,
            if (tN > 0) tSum / tN else -1f, tMax,
            totalDrain, totalDrain / 100f, ch.size,
            if (ch.isNotEmpty()) ch.map { it.lv0 }.average().toFloat() else -1f,
            if (ch.isNotEmpty()) ch.map { it.lv1 }.average().toFloat() else -1f,
            dis.count { it.minLv <= 10 },
            fullMs, chMs, hour, cap
        )
    }
}
