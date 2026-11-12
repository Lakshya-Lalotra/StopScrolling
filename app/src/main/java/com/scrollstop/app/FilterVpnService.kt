package com.scrollstop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Reads DNS questions out of raw IPv4/UDP packets and wraps the answers back up. */
object DnsPacket {
    class Query(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    /** Returns the UDP query in an IPv4 packet, or null for anything else. */
    fun parse(p: ByteArray, len: Int): Query? {
        if (len < 28) return null
        if ((p[0].toInt() ushr 4) and 0xF != 4) return null
        val ihl = (p[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (p[9].toInt() and 0xFF != 17) return null
        if (u16(p, 6) and 0x3FFF != 0) return null // fragments
        val end = minOf(u16(p, 2), len)
        val udpLen = u16(p, ihl + 4)
        val start = ihl + 8
        val stop = minOf(end, ihl + udpLen)
        if (stop <= start) return null
        return Query(
            p.copyOfRange(12, 16), p.copyOfRange(16, 20), u16(p, ihl), u16(p, ihl + 2), p.copyOfRange(start, stop)
        )
    }

    /** An IPv4/UDP packet carrying [dns] back to whoever asked [q]. */
    fun response(q: Query, dns: ByteArray, dnsLen: Int): ByteArray {
        val total = 28 + dnsLen
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 6, 0x4000) // don't fragment
        b[8] = 64
        b[9] = 17
        System.arraycopy(q.dstIp, 0, b, 12, 4)
        System.arraycopy(q.srcIp, 0, b, 16, 4)
        put16(b, 10, checksum(b, 0, 20))
        put16(b, 20, q.dstPort)
        put16(b, 22, q.srcPort)
        put16(b, 24, 8 + dnsLen)
        // UDP checksum 0 = not used, which IPv4 allows
        System.arraycopy(dns, 0, b, 28, dnsLen)
        return b
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += u16(b, i)
            i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }
}

/**
 * Optional adult-content filter. A local VPN that only carries DNS lookups: every lookup is sent to
 * Cloudflare for Families (1.1.1.3), which answers "no such site" for adult and malware sites.
 * All other internet traffic goes straight out as normal; nothing is logged or stored.
 */
class FilterVpnService : VpnService() {

    companion object {
        @Volatile var running = false
            private set
        private const val ACTION_STOP = "com.scrollstop.app.STOP_FILTER"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private val UPSTREAM = listOf("1.1.1.3", "1.0.0.3")

        /** Call after [VpnService.prepare] returned null (permission already given). */
        fun start(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, FilterVpnService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, FilterVpnService::class.java).setAction(ACTION_STOP)) }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Prefs.get(this).adultFilter) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) open()
        return START_STICKY
    }

    private fun open() {
        val b = Builder()
            .setSession("ScrollStop adult filter")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32) // only DNS goes through here
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.setBlocking(true)
            b.setMetered(false)
        }
        val pfd = try {
            b.establish()
        } catch (e: RuntimeException) {
            null
        } ?: return
        tun = pfd
        running = true
        pool = Executors.newFixedThreadPool(4)
        reader = Thread({ loop(pfd) }, "scrollstop-dns").also { it.start() }
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32_767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (tun == null) break
                Thread.sleep(10) // non-blocking read on Android 9 and older
                continue
            }
            if (n <= 0) {
                Thread.sleep(10)
                continue
            }
            val q = DnsPacket.parse(buf, n) ?: continue
            if (q.dstPort != 53) continue
            pool?.execute { answer(q, output) }
        }
    }

    private fun answer(q: DnsPacket.Query, out: FileOutputStream) {
        for (server in UPSTREAM) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 4_000
                    s.send(DatagramPacket(q.payload, q.payload.size, InetAddress.getByName(server), 53))
                    val rb = ByteArray(4_096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    val packet = DnsPacket.response(q, rb, rp.length)
                    synchronized(out) { out.write(packet) }
                }
                return
            } catch (e: IOException) {
                // try the other server
            }
        }
    }

    private fun shutdown() {
        running = false
        reader?.interrupt()
        reader = null
        pool?.shutdownNow()
        pool = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}

/** Turns the adult filter back on after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (Prefs.get(ctx).adultFilter && VpnService.prepare(ctx) == null) FilterVpnService.start(ctx)
        ScrollStopWidget.refresh(ctx)
    }
}
