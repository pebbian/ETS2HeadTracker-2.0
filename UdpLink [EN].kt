package com.example.ets2headtracker

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class UdpLink(context: Context, private val onPc: (String?) -> Unit) {
    private val appContext = context.applicationContext
    private val sendLock = Any()
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var running = false
    @Volatile private var auto: InetSocketAddress? = null
    @Volatile private var manual: InetSocketAddress? = null
    @Volatile private var linkedIp: String? = null
    @Volatile private var lastRx = 0L
    private var poseSeq = 0L
    private var pongSeq = 0L
    private var lastBeat = 0L

    fun start() {
        if (running) return
        running = true
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("headtracker")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        Thread({ loop() }, "udp-link").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        auto = null
        setLinked(null)
    }

    fun setManual(ip: String?, port: Int) {
        manual = if (ip.isNullOrBlank()) null else try {
            InetSocketAddress(InetAddress.getByName(ip.trim()), port)
        } catch (e: Exception) {
            Log.w(TAG, "IP manual tidak valid: ${e.message}")
            null
        }
        lastRx = 0L
        setLinked(null)
        lastBeat = 0L
    }

    fun sendPose(values: FloatArray) {
        val target = manual ?: auto ?: return
        send(Protocol.pack(Protocol.T_POSE, poseSeq++, values), target)
    }

    private fun send(data: ByteArray, target: InetSocketAddress) {
        val s = socket ?: return
        try {
            synchronized(sendLock) {
                s.send(DatagramPacket(data, data.size, target.address, target.port))
            }
        } catch (e: Exception) {
            Log.w(TAG, "kirim gagal: ${e.message}")
        }
    }

    private fun setLinked(ip: String?) {
        if (linkedIp == ip) return
        linkedIp = ip
        onPc(ip)
    }

    private fun touch(ip: String?) {
        lastRx = SystemClock.elapsedRealtime()
        setLinked(ip)
    }

    private fun loop() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 500
                bind(InetSocketAddress(Protocol.PHONE_PORT))
            }
        } catch (e: Exception) {
            Log.e(TAG, "bind gagal: ${e.message}")
            running = false
            return
        }
        socket = s
        val buf = ByteArray(64)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                packet.length = buf.size
                s.receive(packet)
                when (Protocol.type(buf, packet.length)) {
                    Protocol.T_PING -> {
                        val from = InetSocketAddress(packet.address, packet.port)
                        auto = from
                        touch(packet.address.hostAddress)
                        send(Protocol.pack(Protocol.T_PONG, pongSeq++), from)
                        lastBeat = SystemClock.elapsedRealtime()
                    }
                    Protocol.T_ACK -> touch(packet.address.hostAddress)
                }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (running) Log.w(TAG, "terima gagal: ${e.message}")
            }
            beat()
        }
        s.close()
        socket = null
    }

    private fun beat() {
        val now = SystemClock.elapsedRealtime()
        if (linkedIp != null && now - lastRx > LINK_TIMEOUT_MS) setLinked(null)
        if (now - lastBeat < 1000) return
        lastBeat = now
        (manual ?: auto)?.let { send(Protocol.pack(Protocol.T_PONG, pongSeq++), it) }
    }

    private companion object {
        const val TAG = "HeadTracker"
        const val LINK_TIMEOUT_MS = 3000L
    }
}
