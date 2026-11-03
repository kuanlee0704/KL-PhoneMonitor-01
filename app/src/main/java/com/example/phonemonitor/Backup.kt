package com.example.phonemonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** 備份 / 還原:解除安裝或換手機後,紀錄與規則都能還原 */
object Backup {
    const val REQ_PICK = 300      // 選擇備份檔位置
    const val REQ_IMPORT = 301    // 選擇要匯入的檔案

    private fun e(s: String) = s.replace(',', ' ').replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun unb64(s: String) = String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)
    private fun b(v: Boolean) = if (v) 1 else 0

    fun write(ctx: Context, out: OutputStream): Int {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("PMBACKUP 3\n")
        for ((k, t, v) in Prefs.snapshot(ctx)) w.write("P|$k|$t|${b64(v)}\n")
        val db = Db.get(ctx)
        var n = 0
        for (s in db.since(0)) {
            w.write("S|" + listOf(s.ts, s.level, s.status, s.plugged, s.health, s.tempC, s.voltageMv, s.currentMa, s.chargeUah,
                s.fullUah, s.designUah, s.cycle, s.soh, s.memAvailMb, s.memTotalMb, s.storFreeMb, s.storTotalMb, s.rxBytes, s.txBytes,
                e(s.net), s.thermal, b(s.screenOn), b(s.powerSave), s.mobileRx, s.mobileTx).joinToString(",") + "\n")
            n++
        }
        for (ev in db.events(0)) w.write("E|" + listOf(ev.ts, e(ev.kind), ev.endTs, e(ev.fg), b(ev.screenOn), ev.flag, e(ev.info)).joinToString(",") + "\n")
        for (u in db.allUsageRows()) w.write("U|" + u.joinToString(",") { e(it.toString()) } + "\n")
        for (d in db.screenDays(0, Long.MAX_VALUE)) w.write("D|${d.first},${d.second},${d.third}\n")
        w.flush()
        return n
    }

    /** 讀取備份(或舊版匯出的 CSV),回傳結果說明 */
    fun read(ctx: Context, input: InputStream): String {
        val r = input.bufferedReader(Charsets.UTF_8)
        val first = (r.readLine() ?: return "檔案是空的").removePrefix("\uFEFF")
        val db = Db.get(ctx)
        if (first.startsWith("PMBACKUP")) {
            var ns = 0; var ne = 0
            val prefs = ArrayList<Triple<String, String, String>>()
            db.batch {
                r.forEachLine { line ->
                    try {
                        val kind = line.substringBefore('|'); val rest = line.substringAfter('|', "")
                        when (kind) {
                            "P" -> { val a = rest.split("|"); if (a.size >= 3) prefs.add(Triple(a[0], a[1], unb64(a[2]))) }
                            "S" -> {
                                val f = rest.split(",")
                                db.insert(Sample(f[0].toLong(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toFloat(), f[6].toInt(),
                                    f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12].toInt(), f[13].toInt(), f[14].toInt(),
                                    f[15].toLong(), f[16].toLong(), f[17].toLong(), f[18].toLong(), f[19], f[20].toInt(), f[21] == "1", f[22] == "1",
                                    f.getOrNull(23)?.toLongOrNull() ?: -1L, f.getOrNull(24)?.toLongOrNull() ?: -1L)); ns++
                            }
                            "E" -> { val f = rest.split(","); db.restoreEvent(Ev(0, f[0].toLong(), f[1], f[2].toLong(), f[3], f[4] == "1", f[5].toInt(), f.getOrElse(6) { "" })); ne++ }
                            "U" -> { val f = rest.split(","); db.restoreUsageRow(f[0].toLong(), f[1], f[2].toLong(), f[3].toInt(), f[4].toLong()) }
                            "D" -> { val f = rest.split(","); db.restoreScreenDay(f[0].toLong(), f[1].toLong(), f[2].toInt()) }
                        }
                    } catch (_: Exception) {}
                }
            }
            Prefs.restoreAll(ctx, prefs)
            return "已還原 $ns 筆紀錄、$ne 筆事件、${prefs.size} 項設定"
        }
        if (first.contains("電量%")) return importCsv(ctx, r)
        return "無法辨識的檔案格式"
    }

    // 舊版匯出的 CSV(中文欄位)
    private fun importCsv(ctx: Context, r: java.io.BufferedReader): String {
        val db = Db.get(ctx)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun status(s: String) = when (s) { "充電中" -> 2; "放電中" -> 3; "未充電" -> 4; "已充滿" -> 5; else -> 1 }
        fun plug(s: String) = when (s) { "無線" -> 4; "AC" -> 1; "USB" -> 2; "Dock" -> 8; else -> 0 }
        fun health(s: String) = when (s) { "良好" -> 2; "過熱" -> 3; "損壞" -> 4; "過電壓" -> 5; "未知故障" -> 6; "過冷" -> 7; else -> -1 }
        var n = 0
        db.batch {
            r.forEachLine { line ->
                try {
                    val f = line.split(",")
                    if (f.size >= 23) {
                        db.insert(Sample(fmt.parse(f[0])!!.time, f[1].toInt(), status(f[2]), plug(f[3]), health(f[4]), f[5].toFloat(), f[6].toInt(),
                            f[7].toIntOrNull() ?: Int.MIN_VALUE, f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12].toInt(),
                            f[13].toInt(), f[14].toInt(), f[15].toLong(), f[16].toLong(), f[17].toLong(), f[18].toLong(), f[19], f[20].toInt(),
                            f[21] == "true", f[22] == "true")); n++
                    }
                } catch (_: Exception) {}
            }
        }
        return "已匯入 $n 筆(舊版 CSV)"
    }

    fun runNow(ctx: Context): String {
        val s = Prefs.backupUri(ctx)
        if (s.isEmpty()) return "尚未選擇備份檔位置"
        return try {
            ctx.contentResolver.openOutputStream(Uri.parse(s), "wt")!!.use { write(ctx, it) }
            Prefs.setLastBackup(ctx, System.currentTimeMillis()); "ok"
        } catch (ex: Throwable) {
            Prefs.setErr(ctx, "backup: ${ex.javaClass.simpleName}: ${ex.message}"); "失敗:${ex.message}"
        }
    }

    /** 超過 20 小時沒備份就自動備份(需已選擇備份檔位置) */
    fun autoIfDue(ctx: Context) {
        if (Prefs.backupUri(ctx).isEmpty()) return
        if (System.currentTimeMillis() - Prefs.lastBackup(ctx) < 20 * 3_600_000L) return
        runNow(ctx)
    }

    fun pickLocation(act: Activity) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "phonemonitor_backup.pmb")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        act.startActivityForResult(i, REQ_PICK)
    }

    fun pickImport(act: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        act.startActivityForResult(i, REQ_IMPORT)
    }

    /** MainActivity.onActivityResult 轉呼叫;回傳 true 代表已處理 */
    fun onResult(act: Activity, req: Int, res: Int, data: Intent?, done: (String) -> Unit): Boolean {
        if (req != REQ_PICK && req != REQ_IMPORT) return false
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return true
        Thread {
            val msg = try {
                if (req == REQ_PICK) {
                    try { act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                    Prefs.setBackupUri(act, uri.toString())
                    val r = runNow(act); if (r == "ok") "已備份到你選的位置,之後會每天自動更新" else r
                } else act.contentResolver.openInputStream(uri)!!.use { read(act, it) }
            } catch (ex: Throwable) { "失敗:${ex.message}" }
            act.runOnUiThread { done(msg) }
        }.start()
        return true
    }
}
