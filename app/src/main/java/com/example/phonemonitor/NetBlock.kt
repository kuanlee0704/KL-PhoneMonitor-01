package com.example.phonemonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.net.ConnectivityManager
import android.os.Build
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/** 以本機 VPN 把「被選取的 App」的所有網路封包丟進黑洞 => 該 App 無法上網(含背景) */
class NetBlockService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var th: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        stopVpn()
        val pkgs = Prefs.netBlocked(this)
        if (pkgs.isEmpty()) { stopSelf(); return }
        try {
            val b = Builder().setSession("手機監測 · 網路封鎖")
                .addAddress("10.77.0.2", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd00:77::2", 128).addRoute("::", 0)
            var ok = 0
            for (p in pkgs) try { b.addAllowedApplication(p); ok++ } catch (_: Exception) {}
            if (ok == 0) { stopSelf(); return }   // 沒有任何有效 App:不建立,避免把全部流量都擋掉
            pfd = b.establish()
            val fd = pfd ?: return
            th = Thread {
                val ins = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32767)
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val cache = HashMap<String, Int>()
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) try { PacketParser.parse(buf, n, cm, cache)?.let { PacketLog.add(it) } } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }.also { it.start() }
        } catch (e: Throwable) { Prefs.setErr(this, "vpn: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun stopVpn() {
        th?.interrupt(); th = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    override fun onRevoke() { Prefs.setNetBlockOn(this, false); stopVpn(); stopSelf() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }
}

object NetBlock {
    const val REQ = 200

    /** 需要時會跳出系統的 VPN 授權視窗 */
    fun enable(act: Activity) {
        if (Prefs.netBlocked(act).isEmpty()) return
        val i = VpnService.prepare(act)
        if (i != null) act.startActivityForResult(i, REQ) else start(act)
    }

    fun start(ctx: Context) {
        Prefs.setNetBlockOn(ctx, true)
        ctx.startService(Intent(ctx, NetBlockService::class.java))
    }

    fun stop(ctx: Context) {
        Prefs.setNetBlockOn(ctx, false)
        ctx.stopService(Intent(ctx, NetBlockService::class.java))
    }

    /** 清單變更後重新套用 */
    fun refresh(ctx: Context) {
        if (!Prefs.netBlockOn(ctx)) return
        if (Prefs.netBlocked(ctx).isEmpty()) stop(ctx) else ctx.startService(Intent(ctx, NetBlockService::class.java))
    }
}


/** 被封鎖的連線嘗試 */
class Pkt(val ts: Long, val uid: Int, val proto: String, val dst: String, val port: Int, val len: Int, val domain: String)

object PacketLog {
    private val ring = ArrayDeque<Pkt>()
    private val perUid = HashMap<Int, LongArray>()      // [封包數, 位元組]
    private val domains = HashMap<String, Int>()        // "uid|網域" -> 次數

    @Synchronized fun add(p: Pkt) {
        ring.addLast(p); if (ring.size > 300) ring.removeFirst()
        val a = perUid.getOrPut(p.uid) { LongArray(2) }; a[0]++; a[1] += p.len.toLong()
        if (p.domain.isNotEmpty()) { val k = "${p.uid}|${p.domain}"; domains[k] = (domains[k] ?: 0) + 1 }
    }
    @Synchronized fun recent(n: Int): List<Pkt> = ring.toList().takeLast(n).reversed()
    @Synchronized fun totals(): Map<Int, LongArray> = HashMap(perUid)
    @Synchronized fun topDomains(n: Int): List<Pair<String, Int>> = domains.entries.sortedByDescending { it.value }.take(n).map { Pair(it.key, it.value) }
    @Synchronized fun clear() { ring.clear(); perUid.clear(); domains.clear() }
}

object PacketParser {
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun qname(b: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder(); var i = start
        while (i < len) {
            val l = b[i].toInt() and 0xFF
            if (l == 0 || l > 63 || i + 1 + l > len) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, l, Charsets.US_ASCII)); i += 1 + l
        }
        return sb.toString()
    }

    fun parse(buf: ByteArray, len: Int, cm: ConnectivityManager?, cache: HashMap<String, Int>): Pkt? {
        if (len < 20) return null
        val ver = (buf[0].toInt() shr 4) and 0xF
        val proto: Int; val srcB: ByteArray; val dstB: ByteArray; val l4: Int
        if (ver == 4) {
            l4 = (buf[0].toInt() and 0xF) * 4; proto = buf[9].toInt() and 0xFF
            srcB = buf.copyOfRange(12, 16); dstB = buf.copyOfRange(16, 20)
        } else if (ver == 6 && len >= 40) {
            l4 = 40; proto = buf[6].toInt() and 0xFF
            srcB = buf.copyOfRange(8, 24); dstB = buf.copyOfRange(24, 40)
        } else return null
        var sport = 0; var dport = 0
        if ((proto == 6 || proto == 17) && len >= l4 + 4) { sport = u16(buf, l4); dport = u16(buf, l4 + 2) }
        val domain = if (proto == 17 && dport == 53 && len > l4 + 8 + 12) qname(buf, l4 + 8 + 12, len) else ""
        val dst = InetAddress.getByAddress(dstB).hostAddress ?: ""
        var uid = -1
        if (Build.VERSION.SDK_INT >= 29 && cm != null && (proto == 6 || proto == 17)) {
            val key = "$proto:$sport>$dst:$dport"
            uid = cache[key] ?: try {
                cm.getConnectionOwnerUid(proto, InetSocketAddress(InetAddress.getByAddress(srcB), sport), InetSocketAddress(InetAddress.getByAddress(dstB), dport))
            } catch (e: Throwable) { -1 }
            if (cache.size > 400) cache.clear()
            cache[key] = uid
        }
        val name = when (proto) { 6 -> "TCP"; 17 -> "UDP"; 1, 58 -> "ICMP"; else -> "IP$proto" }
        return Pkt(System.currentTimeMillis(), uid, name, dst, dport, len, domain)
    }
}
