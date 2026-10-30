package com.example.phonemonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private lateinit var info: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRange: Button
    private lateinit var btnInterval: Button
    private lateinit var cLevel: ChartView
    private lateinit var cTemp: ChartView
    private lateinit var cCur: ChartView
    private lateinit var cMem: ChartView

    private val rangeHours = longArrayOf(1, 6, 24, 168)
    private val rangeNames = arrayOf("近 1 小時", "近 6 小時", "近 24 小時", "近 7 天")
    private var rangeIdx = 2
    private val intervals = intArrayOf(60, 300, 900, 1800)
    private val reqExport = 100

    private val loop = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 5000) }
    }

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); ui.post(loop) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(loop) }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEach { addView(it) }
    }

    private fun chart(title: String, unit: String) = ChartView(this).also {
        it.title = title; it.unit = unit
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(40), dp(14), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply { text = "手機監測"; textSize = 24f; setTypeface(null, Typeface.BOLD) })

        btnToggle = btn("") { toggle() }
        btnRange = btn("") { rangeIdx = (rangeIdx + 1) % rangeHours.size; refresh() }
        btnInterval = btn("") { cycleInterval() }
        root.addView(row(btnToggle, btnRange, btnInterval))
        root.addView(row(
            btn("匯出 CSV") { export() },
            btn("允許背景執行") { ignoreBatteryOpt() },
            btn("清除資料") { Db.get(this).clear(); refresh() }
        ))

        info = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 13f; setPadding(0, dp(12), 0, dp(12))
            setTextColor(Color.parseColor("#222222"))
        }
        root.addView(info)

        cLevel = chart("電量", "%").also { it.fixedMin = 0f; it.fixedMax = 100f }
        cTemp = chart("電池溫度", "°C").also { it.colorNormal = Color.parseColor("#E65100") }
        cCur = chart("電流(正=充電 負=放電,依機型而異)", "mA")
        cMem = chart("可用記憶體", "MB").also { it.colorNormal = Color.parseColor("#6A1B9A") }
        listOf(cLevel, cTemp, cCur, cMem).forEach { root.addView(it) }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        val i = Intent(this, MonitorService::class.java)
        if (on) startForegroundService(i) else stopService(i)
        refresh()
    }

    private fun cycleInterval() {
        val idx = (intervals.indexOf(Prefs.interval(this)) + 1) % intervals.size
        Prefs.setInterval(this, intervals[idx])
        if (Prefs.enabled(this)) {   // 重啟服務套用新間隔
            val i = Intent(this, MonitorService::class.java)
            stopService(i); startForegroundService(i)
        }
        refresh()
    }

    private fun ignoreBatteryOpt() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已允許背景執行", Toast.LENGTH_SHORT).show(); return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun export() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "phone_monitor_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv")
        }
        startActivityForResult(i, reqExport)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != reqExport || res != RESULT_OK) return
        Thread {
            try {
                val rows = Db.get(this).since(0)
                val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write("\uFEFF時間,電量%,狀態,充電方式,健康,溫度C,電壓mV,電流mA,電荷計數uAh,滿電容量uAh,設計容量uAh,循環次數,SOH%,可用記憶體MB,總記憶體MB,可用儲存MB,總儲存MB,下載累計B,上傳累計B,網路,熱狀態,螢幕亮,省電模式\n")
                    for (s in rows) {
                        w.write(listOf(
                            f.format(Date(s.ts)), s.level, statusName(s.status), plugName(s.plugged), healthName(s.health),
                            s.tempC, s.voltageMv, if (s.currentMa == Int.MIN_VALUE) "" else s.currentMa,
                            s.chargeUah, s.fullUah, s.designUah, s.cycle, s.soh, s.memAvailMb, s.memTotalMb,
                            s.storFreeMb, s.storTotalMb, s.rxBytes, s.txBytes, s.net, s.thermal,
                            s.screenOn, s.powerSave
                        ).joinToString(",") + "\n")
                    }
                }
                runOnUiThread { Toast.makeText(this, "已匯出 ${rows.size} 筆", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "匯出失敗:${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun statusName(s: Int) = when (s) { 2 -> "充電中"; 3 -> "放電中"; 4 -> "未充電"; 5 -> "已充滿"; else -> "未知" }
    private fun plugName(p: Int) = when {
        p and 4 != 0 -> "無線"; p and 1 != 0 -> "AC"; p and 2 != 0 -> "USB"; p and 8 != 0 -> "Dock"; else -> "未接電"
    }
    private fun healthName(h: Int) = when (h) {
        2 -> "良好"; 3 -> "過熱"; 4 -> "損壞"; 5 -> "過電壓"; 6 -> "未知故障"; 7 -> "過冷"; else -> "未知"
    }
    private fun thermalName(t: Int) = when (t) {
        0 -> "正常"; 1 -> "輕微"; 2 -> "中度"; 3 -> "嚴重"; 4 -> "危急"; 5 -> "緊急"; 6 -> "需關機"; else -> "不支援"
    }
    private fun mb(v: Long) = if (v >= 1024) "%.1f GB".format(v / 1024.0) else "$v MB"
    private fun traffic(b: Long) = if (b < 0) "—" else mb(b / 1048576)

    private fun live(s: Sample): String {
        val sb = StringBuilder()
        sb.append("【電池】\n")
        sb.append("電量　　 ${s.level}%  ${statusName(s.status)}  ${plugName(s.plugged)}\n")
        sb.append("健康狀態 ${healthName(s.health)}\n")
        sb.append("溫度　　 ${"%.1f".format(s.tempC)} °C\n")
        sb.append("電壓　　 ${"%.3f".format(s.voltageMv / 1000f)} V\n")
        sb.append("電流　　 ${if (s.currentMa == Int.MIN_VALUE) "不支援" else "${s.currentMa} mA"}\n")
        if (s.currentMa != Int.MIN_VALUE && s.voltageMv > 0)
            sb.append("功率　　 ${"%.2f".format(Math.abs(s.currentMa.toLong() * s.voltageMv) / 1_000_000.0)} W\n")
        if (s.fullUah > 0 && s.designUah > 0)
            sb.append("容量健康 ${s.fullUah / 1000} / ${s.designUah / 1000} mAh = ${s.fullUah * 100 / s.designUah}%\n")
        if (s.soh > 0) sb.append("系統回報健康度 ${s.soh}%\n")
        if (s.level >= 20 && s.chargeUah > 0)
            sb.append("推估容量 ${s.chargeUah * 100L / s.level / 1000} mAh(由剩餘電荷換算)\n")
        if (s.cycle >= 0) sb.append("充電循環 ${s.cycle} 次\n")

        sb.append("\n【系統】\n")
        sb.append("記憶體　 ${mb(s.memAvailMb.toLong())} 可用 / ${mb(s.memTotalMb.toLong())}\n")
        sb.append("儲存空間 ${mb(s.storFreeMb)} 可用 / ${mb(s.storTotalMb)}\n")
        sb.append("網路　　 ${s.net}\n")
        sb.append("流量累計 ↓${traffic(s.rxBytes)}  ↑${traffic(s.txBytes)}(開機後)\n")
        sb.append("熱狀態　 ${thermalName(s.thermal)}\n")
        sb.append("螢幕 ${if (s.screenOn) "亮" else "熄"}　省電模式 ${if (s.powerSave) "開" else "關"}\n")
        sb.append("開機時間 ${android.os.SystemClock.elapsedRealtime() / 3600000} 小時\n")
        return sb.toString()
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        btnToggle.text = if (on) "停止監測" else "開始監測"
        btnRange.text = rangeNames[rangeIdx]
        btnInterval.text = "間隔 ${Prefs.interval(this) / 60} 分"
        val live = try { Collector.read(this) } catch (e: Exception) { null }
        val idx = rangeIdx
        Thread {
            val db = Db.get(this)
            val now = System.currentTimeMillis()
            val rows = db.since(now - rangeHours[idx] * 3600_000L)
            val total = db.count()
            val hour = db.since(now - 3600_000L).filter { !it.charging && it.level >= 0 }
            var rate = ""
            if (hour.size >= 2 && hour.last().ts - hour.first().ts >= 600_000L) {
                val hrs = (hour.last().ts - hour.first().ts) / 3600_000.0
                rate = "近 1 小時耗電 ${"%.1f".format((hour.first().level - hour.last().level) / hrs)} %/小時\n"
            }
            runOnUiThread {
                val head = (if (on) "● 監測中\n" else "○ 未啟動(按「開始監測」)\n") + rate +
                    "資料庫共 $total 筆\n\n"
                info.text = head + (live?.let { live(it) } ?: "讀取失敗")
                val long = rangeHours[idx] > 24
                listOf(cLevel, cTemp, cCur, cMem).forEach { it.longRange = long }
                cLevel.setData(rows.filter { it.level >= 0 }.map { ChartView.Pt(it.ts, it.level.toFloat(), it.charging) })
                cTemp.setData(rows.filter { it.tempC > -50 }.map { ChartView.Pt(it.ts, it.tempC, it.charging) })
                cCur.setData(rows.filter { it.currentMa != Int.MIN_VALUE }.map { ChartView.Pt(it.ts, it.currentMa.toFloat(), it.charging) })
                cMem.setData(rows.map { ChartView.Pt(it.ts, it.memAvailMb.toFloat(), false) })
            }
        }.start()
    }
}
