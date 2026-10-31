package dev.orbit.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

@Serializable
data class PcApp(val name: String, val steam: Int? = null, val cover: String? = null, val hero: String? = null)

@Serializable
data class PcList(val pc: String, val apps: List<PcApp> = emptyList())

/** Зв'язок з програмою Orbit PC (tools/orbit_pc.py): вона знає, які ігри є в Sunshine */
object PcLink {
    const val PORT = 8766
    private val json = Json { ignoreUnknownKeys = true }

    /** Шукає ПК у домашній мережі: Orbit PC відповідає на широкомовний запит */
    suspend fun discover(timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = timeoutMs
                val ask = "ORBIT_PC?".toByteArray()
                s.send(DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), PORT))
                val buf = ByteArray(512)
                val reply = DatagramPacket(buf, buf.size)
                s.receive(reply)
                if (String(buf, 0, reply.length).contains("orbit_pc")) reply.address.hostAddress else null
            }
        }.getOrNull()
    }

    /** Список ігор з ПК; host — IP, ім'я або «ip:порт» */
    suspend fun fetch(host: String, timeoutMs: Int = 4000): PcList? = withContext(Dispatchers.IO) {
        runCatching {
            val addr = if (':' in host) host else "$host:$PORT"
            val c = URL("http://$addr/orbit-pc.json").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            try {
                if (c.responseCode != 200) return@runCatching null
                json.decodeFromString<PcList>(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
