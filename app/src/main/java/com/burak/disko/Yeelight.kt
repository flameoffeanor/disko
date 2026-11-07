package com.burak.disko

import android.content.Context
import android.net.wifi.WifiManager
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Yeelight LAN protokolü (TCP 55443).
 * Music mode: ampul bize bağlanır, komut kotası kalkar → hızlı renk güncellemesi.
 * Music mode açılamazsa normal bağlantıdan ~1 komut/sn ile devam eder.
 */
class YeelightClient(private val host: String) {

    private var control: Socket? = null
    private var music: Socket? = null
    private var out: OutputStream? = null
    private var nextId = 1
    private var lastBright = -1
    private var lastSlowSend = 0L

    var musicMode = false
        private set

    fun connect() {
        val c = Socket()
        c.connect(InetSocketAddress(host, PORT), 3000)
        c.tcpNoDelay = true
        control = c

        // Ampulün gönderdiği cevapları boşalt (buffer dolmasın)
        Thread {
            try {
                val input = c.getInputStream()
                val buf = ByteArray(1024)
                while (input.read(buf) >= 0) { /* discard */ }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        val co = c.getOutputStream()
        send(co, "set_power", "\"on\",\"smooth\",300")

        var server: ServerSocket? = null
        try {
            server = ServerSocket(0)
            server.soTimeout = 4000
            val localIp = c.localAddress.hostAddress
            send(co, "set_music", "1,\"$localIp\",${server.localPort}")
            val m = server.accept()
            m.tcpNoDelay = true
            music = m
            out = m.getOutputStream()
            musicMode = true
        } catch (_: Exception) {
            out = co
            musicMode = false
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    /** rgb: 0xRRGGBB, bright: 1..100 */
    fun setColor(rgb: Int, bright: Int, durationMs: Int) {
        val o = out ?: return
        val color = (rgb and 0xFFFFFF).let { if (it == 0) 1 else it }
        val b = bright.coerceIn(1, 100)

        if (!musicMode) {
            val now = System.currentTimeMillis()
            if (now - lastSlowSend < 1100) return
            lastSlowSend = now
            send(o, "set_scene", "\"color\",$color,$b")
            lastBright = b
            return
        }

        val d = durationMs.coerceAtLeast(30)
        send(o, "set_rgb", "$color,\"smooth\",$d")
        if (b != lastBright) {
            send(o, "set_bright", "$b,\"smooth\",$d")
            lastBright = b
        }
    }

    fun close() {
        out = null
        try { music?.close() } catch (_: Exception) {}
        try { control?.close() } catch (_: Exception) {}
        music = null
        control = null
    }

    private fun send(os: OutputStream, method: String, params: String) {
        val msg = "{\"id\":${nextId++},\"method\":\"$method\",\"params\":[$params]}\r\n"
        os.write(msg.toByteArray())
        os.flush()
    }

    companion object {
        const val PORT = 55443
    }
}

object YeelightDiscovery {
    /** Ağdaki Yeelight ampullerinin IP adreslerini döner (LAN Control açık olmalı). */
    fun discover(ctx: Context, timeoutMs: Long = 3000): List<String> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("disko").apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = LinkedHashSet<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1982\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "ST: wifi_bulb\r\n").toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, addr, 1982)) }

                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                val re = Regex("yeelight://([0-9.]+):")
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(p.data, 0, p.length)
                    re.find(text)?.let { found.add(it.groupValues[1]) }
                }
            }
        } finally {
            lock.release()
        }
        return found.toList()
    }
}
