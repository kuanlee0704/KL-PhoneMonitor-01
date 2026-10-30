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

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "monitor.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, level INTEGER, status INTEGER, plugged INTEGER, " +
                "health INTEGER, tempc REAL, volt INTEGER, curr INTEGER, chgc INTEGER, fullc INTEGER, " +
                "designc INTEGER, cycle INTEGER, soh INTEGER, mema INTEGER, memt INTEGER, stf INTEGER, " +
                "stt INTEGER, rx INTEGER, tx INTEGER, net TEXT, therm INTEGER, scr INTEGER, ps INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

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

    fun clear() { writableDatabase.delete("samples", null, null) }

    /** 只保留最近 N 天,避免資料無限增長 */
    fun trim(keepDays: Int) {
        val cutoff = System.currentTimeMillis() - keepDays * 86400_000L
        writableDatabase.delete("samples", "ts<?", arrayOf(cutoff.toString()))
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
}
