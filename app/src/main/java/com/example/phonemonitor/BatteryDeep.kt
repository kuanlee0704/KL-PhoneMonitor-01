package com.example.phonemonitor

import java.util.TimeZone

class DayStat(
    val day: Long, val drain: Int, val charged: Int, val chargeMs: Long, val dischargeMs: Long, val screenMs: Long,
    val avgTemp: Float, val maxTemp: Float, val chargeCount: Int, val rate: Float, val onRate: Float, val offRate: Float,
    val levelStart: Int, val levelEnd: Int, val rx: Long, val tx: Long, val mobile: Long, val wifi: Long, val samples: Int
) {
    val net: Long get() = rx + tx
    val runtimeH: Float get() = if (rate > 0.05f) 100f / rate else -1f
}

object DayAgg {
    private class Acc {
        var drain = 0; var charged = 0; var chMs = 0L; var disMs = 0L; var scrMs = 0L
        var onDrain = 0; var offDrain = 0; var onMs = 0L; var offMs = 0L; var allDrain = 0; var allMs = 0L
        var tSum = 0f; var tN = 0; var tMax = -100f; var chCount = 0
        var lv0 = -1; var lv1 = -1; var rx = 0L; var tx = 0L; var mob = 0L; var n = 0
    }

    /** 回傳 [fromDay, toDay) 之間每一天(沒資料的天 samples = 0) */
    fun compute(rows: List<Sample>, fromDay: Long, toDay: Long): List<DayStat> {
        val tz = TimeZone.getDefault().getOffset(toDay)
        val DAY = AppUsage.DAY
        fun dayOf(ts: Long) = ((ts + tz) / DAY) * DAY - tz
        val map = LinkedHashMap<Long, Acc>()
        var d = fromDay
        while (d < toDay) { map[d] = Acc(); d += DAY }
        fun rate(x: Int, ms: Long) = if (ms >= 600_000L) x / (ms / 3_600_000f) else -1f

        for (i in rows.indices) {
            val s = rows[i]
            val a = map[dayOf(s.ts)] ?: continue
            a.n++
            if (a.lv0 < 0 && s.level >= 0) a.lv0 = s.level
            if (s.level >= 0) a.lv1 = s.level
            if (s.tempC > -50f) { a.tSum += s.tempC; a.tN++; if (s.tempC > a.tMax) a.tMax = s.tempC }
            if (i == 0) continue
            val p = rows[i - 1]; val dt = s.ts - p.ts
            if (dt <= 0 || dt > 65 * 60_000L) continue
            if (p.charging) a.chMs += dt else a.disMs += dt
            if (p.screenOn) a.scrMs += dt
            if (!p.charging && s.charging) a.chCount++
            if (p.level >= 0 && s.level >= 0) {
                val dl = s.level - p.level
                if (!p.charging && !s.charging) {
                    val drop = maxOf(0, -dl)
                    a.drain += drop; a.allDrain += drop; a.allMs += dt
                    if (p.screenOn) { a.onDrain += drop; a.onMs += dt } else { a.offDrain += drop; a.offMs += dt }
                } else if (p.charging && s.charging && dl > 0) a.charged += dl
            }
            if (p.rxBytes >= 0 && s.rxBytes >= p.rxBytes) a.rx += s.rxBytes - p.rxBytes
            if (p.txBytes >= 0 && s.txBytes >= p.txBytes) a.tx += s.txBytes - p.txBytes
            if (p.mobileRx >= 0 && s.mobileRx >= p.mobileRx) a.mob += (s.mobileRx - p.mobileRx) + maxOf(0L, s.mobileTx - p.mobileTx)
        }
        return map.map { (day, a) ->
            val total = a.rx + a.tx
            DayStat(day, a.drain, a.charged, a.chMs, a.disMs, a.scrMs,
                if (a.tN > 0) a.tSum / a.tN else -1f, a.tMax, a.chCount, rate(a.allDrain, a.allMs), rate(a.onDrain, a.onMs), rate(a.offDrain, a.offMs),
                a.lv0, a.lv1, a.rx, a.tx, minOf(a.mob, total), maxOf(0L, total - a.mob), a.n)
        }
    }
}

class Pt2(val x: Float, val y: Float)

class BatDeep(
    val bandRates: FloatArray, val dischargeCurve: List<Pt2>, val dischargeSessions: Int,
    val chargeLevelCurve: List<Pt2>, val chargePowerCurve: List<Pt2>, val chargeSessions: Int,
    val powerByLevel: List<Pt2>, val voltByLevel: List<Pt2>, val powerHist: FloatArray,
    val plugHist: IntArray, val unplugHist: IntArray, val chargeHourHist: IntArray,
    val heatLabels: List<String>, val heatDrain: Array<FloatArray>, val heatCharge: Array<BooleanArray>, val heatScreen: Array<FloatArray>,
    val onDrain: Int, val offDrain: Int, val onMs: Long, val offMs: Long,
    val overnightCount: Int, val overnightFullH: Float, val chargeMaxTemps: List<Float>
)

object BatDeepAnalysis {
    fun compute(rows: List<Sample>, now: Long): BatDeep? {
        if (rows.size < 2) return null
        val tz = TimeZone.getDefault().getOffset(now)
        val DAY = AppUsage.DAY
        fun hourOf(ts: Long) = (((ts + tz) / 3_600_000L) % 24).toInt()

        // --- 分段(連續同為充電/放電) ---
        class Seg(val i: Int, val j: Int, val charging: Boolean)
        val segs = ArrayList<Seg>()
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j + 1 < rows.size && rows[j + 1].charging == rows[i].charging && rows[j + 1].ts - rows[j].ts <= 65 * 60_000L) j++
            if (j > i) segs.add(Seg(i, j, rows[i].charging))
            i = j + 1
        }

        // --- 成對資料:分段耗電速率、各時段、功率分布、熱圖 ---
        val bandDrop = IntArray(5); val bandMs = LongArray(5)
        var onDrain = 0; var offDrain = 0; var onMs = 0L; var offMs = 0L
        val powerHist = FloatArray(12)
        val today = AppUsage.dayStart(now)
        val heatDrain = Array(14) { FloatArray(24) }; val heatCharge = Array(14) { BooleanArray(24) }; val heatScreen = Array(14) { FloatArray(24) }
        val heatLabels = (0 until 14).map { k -> val dd = java.util.Date(today - (13 - k) * DAY + 12 * 3_600_000L); java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault()).format(dd) }
        fun rowOf(ts: Long): Int { val r = 13 - ((today - ((ts + tz) / DAY * DAY - tz)) / DAY).toInt(); return r }

        for (k in 1 until rows.size) {
            val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
            if (dt <= 0 || dt > 65 * 60_000L || a.level < 0 || b.level < 0) continue
            val r = rowOf(b.ts); val h = hourOf(b.ts)
            if (r in 0..13) {
                if (a.charging || b.charging) heatCharge[r][h] = true
                if (a.screenOn) heatScreen[r][h] += dt / 60_000f
            }
            if (!a.charging && !b.charging) {
                val drop = maxOf(0, a.level - b.level)
                val band = ((100 - a.level) / 20).coerceIn(0, 4)
                bandDrop[band] += drop; bandMs[band] += dt
                if (a.screenOn) { onDrain += drop; onMs += dt } else { offDrain += drop; offMs += dt }
                if (r in 0..13) heatDrain[r][h] += drop.toFloat()
                if (a.currentMa != Int.MIN_VALUE && a.voltageMv > 0) {
                    val w = Math.abs(a.currentMa.toLong() * a.voltageMv) / 1_000_000f
                    powerHist[minOf(11, (w / 0.5f).toInt())] += dt / 60_000f   // 以「分鐘」累計
                }
            }
        }
        val bandRates = FloatArray(5) { if (bandMs[it] >= 20 * 60_000L) bandDrop[it] / (bandMs[it] / 3_600_000f) else -1f }

        // --- 平均放電曲線(自拔掉充電器起) ---
        val dsum = FloatArray(60); val dcnt = IntArray(60); var dSess = 0
        for (s in segs) if (!s.charging) {
            val t0 = rows[s.i].ts; val dur = rows[s.j].ts - t0
            if (dur < 2 * 3_600_000L || rows[s.i].level < 50) continue
            dSess++
            var kIdx = s.i
            var hh = 0
            while (hh < 60) {
                val t = t0 + hh * 3_600_000L
                if (t > rows[s.j].ts) break
                while (kIdx + 1 <= s.j && rows[kIdx + 1].ts <= t) kIdx++
                dsum[hh] += rows[kIdx].level; dcnt[hh]++; hh++
            }
        }
        val minCnt = maxOf(1, dSess / 3)
        val dCurve = (0 until 60).filter { dcnt[it] >= minCnt }.map { Pt2(it.toFloat(), dsum[it] / dcnt[it]) }

        // --- 平均充電曲線(自插上充電器起,每 10 分鐘) ---
        val lSum = FloatArray(25); val lCnt = IntArray(25); val pSum = FloatArray(25); val pCnt = IntArray(25); var cSess = 0
        val plug = IntArray(10); val unplug = IntArray(10); val hourHist = IntArray(24)
        val chMax = ArrayList<Float>()
        var overnight = 0; var fullMsNight = 0L
        for (s in segs) if (s.charging) {
            val t0 = rows[s.i].ts; val dur = rows[s.j].ts - t0
            plug[(rows[s.i].level / 10).coerceIn(0, 9)]++; unplug[(rows[s.j].level / 10).coerceIn(0, 9)]++
            hourHist[hourOf(t0)]++
            var mt = -100f; for (q in s.i..s.j) if (rows[q].tempC > mt) mt = rows[q].tempC
            if (mt > -50f) chMax.add(mt)
            val hs = hourOf(t0)
            if ((hs >= 20 || hs <= 2) && dur >= 4 * 3_600_000L) {
                overnight++
                for (q in s.i + 1..s.j) if (rows[q - 1].level >= 100) fullMsNight += rows[q].ts - rows[q - 1].ts
            }
            if (dur >= 20 * 60_000L && rows[s.i].level <= 60) {
                cSess++
                for (q in s.i..s.j) {
                    val m = ((rows[q].ts - t0) / 600_000L).toInt()
                    if (m > 24) break
                    lSum[m] += rows[q].level; lCnt[m]++
                    val x = rows[q]
                    if (x.currentMa != Int.MIN_VALUE && x.voltageMv > 0) { pSum[m] += Math.abs(x.currentMa.toLong() * x.voltageMv) / 1_000_000f; pCnt[m]++ }
                }
            }
        }
        val lCurve = (0..24).filter { lCnt[it] > 0 }.map { Pt2(it * 10f, lSum[it] / lCnt[it]) }
        val pCurve = (0..24).filter { pCnt[it] > 0 }.map { Pt2(it * 10f, pSum[it] / pCnt[it]) }

        // --- 功率 vs 電量(充電)、電壓 vs 電量(放電) ---
        val pw = FloatArray(21); val pwN = IntArray(21); val vv = FloatArray(21); val vvN = IntArray(21)
        for (s in rows) {
            if (s.level < 0) continue
            val bk = (s.level / 5).coerceIn(0, 20)
            if (s.charging && s.currentMa != Int.MIN_VALUE && s.voltageMv > 0) { pw[bk] += Math.abs(s.currentMa.toLong() * s.voltageMv) / 1_000_000f; pwN[bk]++ }
            if (!s.charging && s.voltageMv > 0) { vv[bk] += s.voltageMv / 1000f; vvN[bk]++ }
        }
        val pByL = (0..20).filter { pwN[it] >= 2 }.map { Pt2(it * 5f, pw[it] / pwN[it]) }
        val vByL = (0..20).filter { vvN[it] >= 2 }.map { Pt2(it * 5f, vv[it] / vvN[it]) }

        return BatDeep(bandRates, dCurve, dSess, lCurve, pCurve, cSess, pByL, vByL, powerHist, plug, unplug, hourHist,
            heatLabels, heatDrain, heatCharge, heatScreen, onDrain, offDrain, onMs, offMs, overnight, fullMsNight / 3_600_000f, chMax)
    }
}
