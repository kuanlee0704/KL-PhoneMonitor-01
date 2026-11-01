package com.example.phonemonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 一筆紀錄。未知數值一律為 -1(電流未知為 Int.MIN_VALUE) */
data class Sample(
    val ts: Long,
    val level: Int, val status: Int, val plugged: Int, val health: Int,
    val tempC: Float, val voltageMv: Int, val currentMa: Int,
    val chargeUah: Int, val fullUah: Int, val designUah: Int,
    val cycle: Int, val soh: Int,
    val memAvailMb: Int, val memTotalMb: Int,
    val storFreeMb: Long, val storTotalMb: Long,
    val rxBytes: Long, val txBytes: Long, val net: String,
    val thermal: Int, val screenOn: Boolean, val powerSave: Boolean
) {
    val charging: Boolean get() = status == 2 || status == 5
}

class Ev(
    val id: Long, val ts: Long, val kind: String, val endTs: Long,
    val fg: String, val screenOn: Boolean, val flag: Int, val info: String
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "monitor.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, level INTEGER, status INTEGER, plugged INTEGER, " +
                "health INTEGER, tempc REAL, volt INTEGER, curr INTEGER, chgc INTEGER, fullc INTEGER, " +
                "designc INTEGER, cycle INTEGER, soh INTEGER, mema INTEGER, memt INTEGER, stf INTEGER, " +
                "stt INTEGER, rx INTEGER, tx INTEGER, net TEXT, therm INTEGER, scr INTEGER, ps INTEGER)"
        )
        createEvents(db)
    }

    private fun createEvents(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, " +
                "endts INTEGER, fg TEXT, scr INTEGER, flag INTEGER, info TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { if (o < 2) createEvents(db) }

    fun startEvent(kind: String, fg: String, scr: Boolean, flag: Int, info: String): Long {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind); put("endts", 0L); put("fg", fg)
            put("scr", if (scr) 1 else 0); put("flag", flag); put("info", info)
        }
        return writableDatabase.insert("events", null, cv)
    }

    fun endEvent(id: Long) {
        val cv = ContentValues().apply { put("endts", System.currentTimeMillis()) }
        writableDatabase.update("events", cv, "id=?", arrayOf(id.toString()))
    }

    fun events(since: Long): List<Ev> {
        val out = ArrayList<Ev>()
        readableDatabase.rawQuery(
            "SELECT id,ts,kind,endts,fg,scr,flag,info FROM events WHERE ts>=? ORDER BY ts DESC",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                Ev(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getLong(3), c.getString(4) ?: "",
                    c.getInt(5) == 1, c.getInt(6), c.getString(7) ?: "")
            )
        }
        return out
    }

    fun clearEvents() { writableDatabase.delete("events", null, null) }

    fun insert(s: Sample) {
        val cv = ContentValues().apply {
            put("ts", s.ts); put("level", s.level); put("status", s.status); put("plugged", s.plugged)
            put("health", s.health); put("tempc", s.tempC.toDouble()); put("volt", s.voltageMv)
            put("curr", s.currentMa); put("chgc", s.chargeUah); put("fullc", s.fullUah)
            put("designc", s.designUah); put("cycle", s.cycle); put("soh", s.soh)
            put("mema", s.memAvailMb); put("memt", s.memTotalMb); put("stf", s.storFreeMb)
            put("stt", s.storTotalMb); put("rx", s.rxBytes); put("tx", s.txBytes); put("net", s.net)
            put("therm", s.thermal); put("scr", if (s.screenOn) 1 else 0); put("ps", if (s.powerSave) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun since(ts: Long): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT ts,level,status,plugged,health,tempc,volt,curr,chgc,fullc,designc,cycle,soh,mema,memt," +
                "stf,stt,rx,tx,net,therm,scr,ps FROM samples WHERE ts>=? ORDER BY ts",
            arrayOf(ts.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Sample(
                        c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4),
                        c.getFloat(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9), c.getInt(10),
                        c.getInt(11), c.getInt(12), c.getInt(13), c.getInt(14),
                        c.getLong(15), c.getLong(16), c.getLong(17), c.getLong(18), c.getString(19) ?: "",
                        c.getInt(20), c.getInt(21) == 1, c.getInt(22) == 1
                    )
                )
            }
        }
        return out
    }

    fun count(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM samples", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    fun lastTs(): Long = readableDatabase.rawQuery("SELECT MAX(ts) FROM samples", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    fun clear() { writableDatabase.delete("samples", null, null) }

    /** 只保留最近 N 天,避免資料無限增長 */
    fun trim(keepDays: Int) {
        val cutoff = System.currentTimeMillis() - keepDays * 86400_000L
        writableDatabase.delete("samples", "ts<?", arrayOf(cutoff.toString()))
        writableDatabase.delete("events", "ts<?", arrayOf(cutoff.toString()))
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun interval(c: Context): Int = p(c).getInt("interval", 300)
    fun setInterval(c: Context, v: Int) = p(c).edit().putInt("interval", v).apply()
    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    fun lastOk(c: Context): Long = p(c).getLong("lastOk", 0L)
    fun setOk(c: Context, ts: Long) = p(c).edit().putLong("lastOk", ts).apply()
    fun lastErr(c: Context): String = p(c).getString("lastErr", "") ?: ""
    fun setErr(c: Context, m: String) = p(c).edit().putString("lastErr", "${java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $m").apply()

    // ----- 使用上限 / 流量設定 -----
    fun limits(c: Context): MutableMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        for (part in (p(c).getString("limits", "") ?: "").split(";")) {
            val kv = part.split("=")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { m[kv[0]] = it }
        }
        return m
    }
    fun setLimit(c: Context, pkg: String, min: Int) {
        val m = limits(c)
        if (min <= 0) m.remove(pkg) else m[pkg] = min
        p(c).edit().putString("limits", m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
        Guard.invalidate()
    }
    fun totalLimit(c: Context): Int = p(c).getInt("totalLimit", 0)
    fun setTotalLimit(c: Context, min: Int) = p(c).edit().putInt("totalLimit", min).apply()
    fun dataLimitGb(c: Context): Float = p(c).getFloat("dataLimitGb", 0f)
    fun setDataLimitGb(c: Context, v: Float) = p(c).edit().putFloat("dataLimitGb", v).apply()
    fun billingDay(c: Context): Int = p(c).getInt("billingDay", 1)
    fun setBillingDay(c: Context, v: Int) = p(c).edit().putInt("billingDay", v).apply()

    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun notifiedToday(c: Context, key: String): Boolean {
        val sp = p(c)
        if (sp.getString("doneDay", "") != today()) {
            sp.edit().putString("doneDay", today()).putStringSet("doneKeys", HashSet()).apply(); return false
        }
        return (sp.getStringSet("doneKeys", emptySet()) ?: emptySet()).contains(key)
    }
    fun markNotified(c: Context, key: String) {
        val sp = p(c)
        val set = HashSet(sp.getStringSet("doneKeys", emptySet()) ?: emptySet())
        set.add(key)
        sp.edit().putStringSet("doneKeys", set).putString("doneDay", today()).apply()
    }

    // ----- 封鎖 / 專注 / 短影音 -----
    private fun strSet(c: Context, k: String): Set<String> = p(c).getStringSet(k, emptySet()) ?: emptySet()
    private fun putSet(c: Context, k: String, s: Set<String>) { p(c).edit().putStringSet(k, HashSet(s)).apply(); Guard.invalidate() }
    fun blocked(c: Context): Set<String> = strSet(c, "blocked")
    fun setBlocked(c: Context, s: Set<String>) = putSet(c, "blocked", s)
    fun toggleBlocked(c: Context, pkg: String) { val s = HashSet(blocked(c)); if (!s.remove(pkg)) s.add(pkg); setBlocked(c, s) }
    fun focusApps(c: Context): Set<String> = strSet(c, "focusApps")
    fun setFocusApps(c: Context, s: Set<String>) = putSet(c, "focusApps", s)
    fun toggleFocus(c: Context, pkg: String) { val s = HashSet(focusApps(c)); if (!s.remove(pkg)) s.add(pkg); setFocusApps(c, s) }
    fun netBlocked(c: Context): Set<String> = strSet(c, "netBlocked")
    fun setNetBlocked(c: Context, s: Set<String>) = putSet(c, "netBlocked", s)
    fun toggleNetBlocked(c: Context, pkg: String) { val s = HashSet(netBlocked(c)); if (!s.remove(pkg)) s.add(pkg); setNetBlocked(c, s) }
    fun netBlockOn(c: Context): Boolean = p(c).getBoolean("netBlockOn", false)
    fun setNetBlockOn(c: Context, v: Boolean) = p(c).edit().putBoolean("netBlockOn", v).apply()

    fun focusUntil(c: Context): Long = p(c).getLong("focusUntil", 0L)
    fun setFocusUntil(c: Context, v: Long) = p(c).edit().putLong("focusUntil", v).apply()
    fun schedOn(c: Context): Boolean = p(c).getBoolean("schedOn", false)
    fun setSchedOn(c: Context, v: Boolean) = p(c).edit().putBoolean("schedOn", v).apply()
    fun schedStart(c: Context): Int = p(c).getInt("schedStart", 22 * 60)
    fun schedEnd(c: Context): Int = p(c).getInt("schedEnd", 7 * 60)
    fun setSched(c: Context, start: Int, end: Int) = p(c).edit().putInt("schedStart", start).putInt("schedEnd", end).apply()
    fun limitBlock(c: Context): Boolean = p(c).getBoolean("limitBlock", false)
    fun setLimitBlock(c: Context, v: Boolean) { p(c).edit().putBoolean("limitBlock", v).apply(); Guard.invalidate() }
    fun strict(c: Context): Boolean = p(c).getBoolean("strict", false)
    fun setStrict(c: Context, v: Boolean) = p(c).edit().putBoolean("strict", v).apply()

    /** 短影音模式:-1 關閉 / 0 完全封鎖 / N 每日額度(分鐘) */
    fun feature(c: Context, key: String): Int = p(c).getInt("feat_$key", -1)
    fun setFeature(c: Context, key: String, v: Int) = p(c).edit().putInt("feat_$key", v).apply()

    private fun dayVal(c: Context, k: String): Long {
        val parts = (p(c).getString(k, "") ?: "").split(":")
        return if (parts.size == 2 && parts[0] == today()) parts[1].toLongOrNull() ?: 0L else 0L
    }
    fun featUsedMs(c: Context, key: String): Long = dayVal(c, "fu_$key")
    fun addFeatUsed(c: Context, key: String, ms: Long) =
        p(c).edit().putString("fu_$key", "${today()}:${featUsedMs(c, key) + ms}").apply()
    fun featBlocks(c: Context, key: String): Int = dayVal(c, "fb_$key").toInt()
    fun incFeatBlocks(c: Context, key: String) =
        p(c).edit().putString("fb_$key", "${today()}:${featBlocks(c, key) + 1}").apply()

    // ----- 密碼 -----
    fun pinHash(c: Context): String = p(c).getString("pinHash", "") ?: ""
    fun pinSalt(c: Context): String = p(c).getString("pinSalt", "") ?: ""
    fun pinLen(c: Context): Int = p(c).getInt("pinLen", 0)
    fun savePin(c: Context, hash: String, salt: String, len: Int) =
        p(c).edit().putString("pinHash", hash).putString("pinSalt", salt).putInt("pinLen", len).putInt("pinFails", 0).putLong("pinLockUntil", 0L).apply()
    fun clearPin(c: Context) = p(c).edit().remove("pinHash").remove("pinSalt").remove("pinLen").putInt("pinFails", 0).apply()
    fun pinFails(c: Context): Int = p(c).getInt("pinFails", 0)
    fun setPinFails(c: Context, v: Int) = p(c).edit().putInt("pinFails", v).apply()
    fun pinLockUntil(c: Context): Long = p(c).getLong("pinLockUntil", 0L)
    fun setPinLockUntil(c: Context, v: Long) = p(c).edit().putLong("pinLockUntil", v).apply()
    fun lockOnOpen(c: Context): Boolean = p(c).getBoolean("lockOnOpen", true)
    fun setLockOnOpen(c: Context, v: Boolean) = p(c).edit().putBoolean("lockOnOpen", v).apply()

    // ----- 自訂 / 信任 -----
    fun snoozeMin(c: Context): Int = p(c).getInt("snoozeMin", 5)
    fun setSnoozeMin(c: Context, v: Int) = p(c).edit().putInt("snoozeMin", v).apply()
    fun trusted(c: Context): Set<String> = strSet(c, "trusted")
    fun toggleTrusted(c: Context, pkg: String) {
        val s = HashSet(trusted(c)); if (!s.remove(pkg)) s.add(pkg); putSet(c, "trusted", s)
    }
    fun customIds(c: Context, key: String): String = p(c).getString("cid_$key", "") ?: ""
    fun setCustomIds(c: Context, key: String, v: String) = p(c).edit().putString("cid_$key", v.trim()).apply()
}
