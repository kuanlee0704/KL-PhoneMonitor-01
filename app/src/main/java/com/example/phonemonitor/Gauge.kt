package com.example.phonemonitor

class CliffEvent(val ts: Long, val from: Int, val to: Int, val minutes: Int)
class ShutEvent(val ts: Long, val level: Int, val voltMv: Int, val tempC: Float, val gapMs: Long, val levelAfter: Int)

class GaugeResult(
    val n: Int,                 // 用於分析的放電樣本數
    val sign: Int,              // 放電時電流的正負號(-1 = 放電為負)
    val rOhm: Float,            // 估計內阻(Ω)
    val fitOk: Boolean,         // 內阻擬合是否可信
    val r2: Float,              // 擬合 R²
    val vMax: Float,            // 推估滿電電壓
    val lastReported: Int,      // 最近系統回報電量
    val lastEst: Float,         // 最近電壓推估電量(-1 = 無法估算)
    val avgLoadA: Float,        // 最近平均放電電流(A)
    val series: List<Triple<Long, Float, Float>>,   // 時間, 系統電量, 推估電量(已平滑)
    val bins: List<Triple<Int, Float, Int>>,        // 系統電量區間(下限) → 平均推估電量, 樣本數
    val lowVoltFrac: Float,     // 電量 > 15% 時電壓 < 3.5V 的比例
    val cliffs: List<CliffEvent>, val shutdowns: List<ShutEvent>
)

/**
 * 以「電壓 − 內阻 × 負載電流」還原開路電壓,再對照典型鋰電池的 電壓→電量 曲線,
 * 估算較接近真實的剩餘電量,用來和系統顯示的百分比比對。
 * 這是估算(典型曲線 + 回歸內阻),誤差約 ±10%,不是精密量測。
 */
object GaugeAnalysis {
    private val SOC = FloatArray(21) { it * 5f }
    private val OCV = floatArrayOf(3.30f, 3.45f, 3.61f, 3.69f, 3.71f, 3.73f, 3.75f, 3.77f, 3.79f, 3.80f, 3.82f,
        3.84f, 3.87f, 3.91f, 3.95f, 3.98f, 4.02f, 4.08f, 4.11f, 4.15f, 4.20f)

    fun socFromV(v: Float, vMax: Float): Float {
        val ve = 3.30f + (v - 3.30f) * 0.90f / (maxOf(vMax, 4.1f) - 3.30f)   // 高壓電芯(4.35/4.4V)按比例縮放
        if (ve <= OCV[0]) return 0f
        if (ve >= OCV[20]) return 100f
        for (i in 1..20) if (ve <= OCV[i]) return SOC[i - 1] + (ve - OCV[i - 1]) / (OCV[i] - OCV[i - 1]) * 5f
        return 100f
    }

    /** 單筆樣本的推估電量;充電中 / 沒有電流資料時回傳 null */
    fun estimate(s: Sample, rOhm: Float, vMax: Float, sign: Int): Float? {
        if (s.charging || s.currentMa == Int.MIN_VALUE || s.voltageMv <= 0) return null
        val load = (sign * s.currentMa / 1000f).coerceAtLeast(0f)
        return socFromV(s.voltageMv / 1000f + rOhm * load, vMax)
    }

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val n = 3
        val a = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) m[i][j] else v[i] } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (Math.abs(a[r][c]) > Math.abs(a[p][c])) p = r
            if (Math.abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until n) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c..n) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    fun compute(all: List<Sample>, now: Long): GaugeResult? {
        val rows = all.takeLast(6000)
        if (rows.size < 20) return null
        val NONE = Int.MIN_VALUE

        // 1) 放電電流的正負號
        val chg = rows.filter { it.charging && it.currentMa != NONE && Math.abs(it.currentMa) > 50 }
        val sign = if (chg.size >= 10) { if (chg.count { it.currentMa > 0 } * 2 >= chg.size) -1 else 1 }
        else {
            val dis = rows.filter { !it.charging && it.currentMa != NONE && Math.abs(it.currentMa) > 50 }
            if (dis.isEmpty() || dis.count { it.currentMa < 0 } * 2 >= dis.size) -1 else 1
        }

        // 2) 推估滿電電壓
        val full = rows.filter { it.level >= 97 && it.voltageMv > 3900 }.map { it.voltageMv / 1000f }.sorted()
        val vMax = if (full.size >= 5) full[(full.size * 0.9f).toInt().coerceAtMost(full.size - 1)].coerceIn(4.15f, 4.5f) else 4.20f

        // 3) 迴歸:V = a + b·負載(A) + c·電量,內阻 R = −b
        val xs = ArrayList<DoubleArray>(); val ys = ArrayList<Double>()
        for (s in rows) {
            if (s.charging || s.currentMa == NONE || s.voltageMv <= 0 || s.level !in 10..95) continue
            val load = sign * s.currentMa / 1000.0
            if (load < -0.05) continue
            xs.add(doubleArrayOf(1.0, load, s.level / 100.0)); ys.add(s.voltageMv / 1000.0)
        }
        var r = 0.15f; var fitOk = false; var r2 = 0f
        if (xs.size >= 30) {
            val m = Array(3) { DoubleArray(3) }; val v = DoubleArray(3)
            for (k in xs.indices) for (i in 0 until 3) { v[i] += xs[k][i] * ys[k]; for (j in 0 until 3) m[i][j] += xs[k][i] * xs[k][j] }
            val beta = solve3(m, v)
            if (beta != null) {
                val mean = ys.average()
                var ssT = 0.0; var ssR = 0.0
                for (k in xs.indices) {
                    val pred = beta[0] * xs[k][0] + beta[1] * xs[k][1] + beta[2] * xs[k][2]
                    ssT += (ys[k] - mean) * (ys[k] - mean); ssR += (ys[k] - pred) * (ys[k] - pred)
                }
                r2 = if (ssT > 1e-9) (1.0 - ssR / ssT).toFloat() else 0f
                if (beta[1] < -0.02 && r2 > 0.03) { r = (-beta[1]).toFloat().coerceIn(0.03f, 1.0f); fitOk = true }
            }
        }

        // 4) 逐筆推估 + 平滑
        val pts = ArrayList<Triple<Long, Float, Float>>()
        val bin = FloatArray(10); val binN = IntArray(10)
        var lowV = 0; var lowN = 0
        val est = rows.map { estimate(it, r, vMax, sign) }
        val win = 5
        for (i in rows.indices) {
            val e = est[i] ?: continue
            var sum = 0f; var c = 0
            for (k in maxOf(0, i - win + 1)..i) est[k]?.let { sum += it; c++ }
            val sm = sum / c
            if (now - rows[i].ts <= 48 * 3_600_000L) pts.add(Triple(rows[i].ts, rows[i].level.toFloat(), sm))
            if (rows[i].level >= 0) { val b = (rows[i].level / 10).coerceIn(0, 9); bin[b] += sm; binN[b]++ }
            if (rows[i].level > 15) { lowN++; if (rows[i].voltageMv < 3500) lowV++ }
        }
        val bins = (0 until 10).filter { binN[it] >= 3 }.map { Triple(it * 10, bin[it] / binN[it], binN[it]) }

        // 5) 最近狀態
        val recent = rows.indices.filter { est[it] != null && now - rows[it].ts <= 3 * 3_600_000L }.takeLast(6)
        val lastRep = if (recent.isNotEmpty()) recent.map { rows[it].level }.average().toInt() else rows.last().level
        val lastEst = if (recent.isNotEmpty()) recent.map { est[it]!! }.average().toFloat() else -1f
        val avgLoad = if (recent.isNotEmpty()) recent.map { Math.max(0f, sign * rows[it].currentMa / 1000f) }.average().toFloat() else 0f

        // 6) 電量斷崖 / 疑似意外關機
        val cliffs = ArrayList<CliffEvent>(); val shuts = ArrayList<ShutEvent>()
        for (k in 1 until rows.size) {
            val a = rows[k - 1]; val b = rows[k]; val dt = b.ts - a.ts
            if (a.level < 0 || b.level < 0 || a.charging || b.charging) continue
            if (dt in 1..(10 * 60_000L) && a.level - b.level >= 5) cliffs.add(CliffEvent(b.ts, a.level, b.level, (dt / 60_000L).toInt().coerceAtLeast(1)))
            val reboot = a.rxBytes >= 0 && b.rxBytes >= 0 && b.rxBytes < a.rxBytes
            if (reboot && dt > 2 * 60_000L && a.level in 1..40) shuts.add(ShutEvent(a.ts, a.level, a.voltageMv, a.tempC, dt, b.level))
        }
        return GaugeResult(xs.size, sign, r, fitOk, r2, vMax, lastRep, lastEst, avgLoad, pts, bins,
            if (lowN > 0) lowV.toFloat() / lowN else 0f, cliffs.takeLast(10).reversed(), shuts.takeLast(8).reversed())
    }
}
