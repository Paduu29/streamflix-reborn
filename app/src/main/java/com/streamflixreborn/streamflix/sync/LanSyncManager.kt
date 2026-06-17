package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.backup.BackupRestoreManager
import com.streamflixreborn.streamflix.backup.ProviderBackupContext
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class LanSyncManager(private val context: Context) {

    private var server: SyncServer? = null
    @Volatile private var isSyncing = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _backupManager by lazy { createBackupManager() }
    private fun backupManager() = _backupManager

    companion object {
        const val PORT = 8765
        const val DISCOVERY_PORT = 8766
        private const val TAG = "LanSync"

        fun getDeviceName(): String {
            val saved = UserPreferences.syncDeviceName
            if (saved.isNotBlank()) return saved
            return android.os.Build.MODEL
        }
    }

    fun startServer() {
        stopServer()
        server = SyncServer(PORT).apply {
            try {
                start()
                Log.i(TAG, "Server started on port $PORT (${getLocalIpForDisplay()})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server?.isAlive == true

    suspend fun pushToAllPeers(): Boolean {
        if (!UserPreferences.lanSyncEnabled || !isRunning()) return false
        val peers = UserPreferences.syncPeers
        if (peers.isEmpty()) return false

        val json = withContext(Dispatchers.IO) {
            backupManager().exportUserData()
        } ?: return false

        var anySuccess = false
        for (peer in peers) {
            try {
                withContext(Dispatchers.IO) { pushToPeer(peer, json) }
                anySuccess = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push to $peer: ${e.message}")
            }
        }
        return anySuccess
    }

    private fun pushToPeer(ip: String, json: String) {
        val request = Request.Builder()
            .url("http://$ip:$PORT/sync/data")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().close()
    }

    suspend fun pullFromAllPeers(): Boolean {
        if (!UserPreferences.lanSyncEnabled) return false
        val peers = UserPreferences.syncPeers
        if (peers.isEmpty()) return false

        var latestJson: String? = null
        var latestTimestamp = UserPreferences.lastSyncTimestamp

        for (peer in peers) {
            try {
                val result = withContext(Dispatchers.IO) { fetchFromPeer(peer) } ?: continue
                val timestamp = parseExportedAt(result)
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp
                    latestJson = result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pull from $peer: ${e.message}")
            }
        }

        if (latestJson != null) {
            isSyncing = true
            try {
                withContext(Dispatchers.IO) {
                    backupManager().importUserData(latestJson)
                }
                UserPreferences.lastSyncTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Sync pull complete")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Import failed during pull", e)
            } finally {
                isSyncing = false
            }
        }
        return false
    }

    private fun fetchFromPeer(ip: String): String? {
        val request = Request.Builder()
            .url("http://$ip:$PORT/sync/data")
            .get()
            .build()
        return client.newCall(request).execute().body?.string()
    }

    private fun parseExportedAt(json: String): Long {
        return try {
            JSONObject(json).optLong("exportedAt", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    fun discoverPeers(timeoutMs: Long = 2000): List<String> {
        val discovered = mutableListOf<String>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                soTimeout = timeoutMs.toInt()
            }
            val discoveryMsg = "STREAMFLIX_DISCOVER"
            val packet = DatagramPacket(
                discoveryMsg.toByteArray(),
                discoveryMsg.length,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket.send(packet)

            val buf = ByteArray(256)
            val localIp = getLocalIpAddress()
            while (true) {
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val ip = recv.address.hostAddress
                    val msg = String(recv.data, 0, recv.length)
                    if (msg.startsWith("STREAMFLIX_HERE") && ip != null && ip != localIp && ip !in discovered) {
                        discovered.add(ip)
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Discovery error", e)
        } finally {
            socket?.close()
        }
        return discovered
    }

    fun respondToDiscovery() {
        Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 500
                }
                val buf = ByteArray(256)
                while (true) {
                    try {
                        val recv = DatagramPacket(buf, buf.size)
                        socket.receive(recv)
                        val msg = String(recv.data, 0, recv.length)
                        if (msg == "STREAMFLIX_DISCOVER") {
                            val response = "STREAMFLIX_HERE:${getDeviceName()}"
                            val pkt = DatagramPacket(
                                response.toByteArray(),
                                response.length,
                                recv.address,
                                recv.port
                            )
                            socket.send(pkt)
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                }
            } catch (e: Exception) {
                // port may be in use, ignore
            } finally {
                socket?.close()
            }
        }.start()
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getLocalIpForDisplay(): String = getLocalIpAddress() ?: "Unknown"

    fun isSyncingInProgress(): Boolean = isSyncing

    private fun createBackupManager(): BackupRestoreManager {
        val allProviders = Provider.providers.keys.toMutableList().apply {
            listOf("it", "en", "es", "de", "fr").forEach { lang ->
                add(TmdbProvider(lang))
            }
        }
        return BackupRestoreManager(
            context,
            allProviders.mapNotNull { provider ->
                try {
                    val db = AppDatabase.getInstanceForProvider(provider.name, context)
                    ProviderBackupContext(
                        name = provider.name,
                        movieDao = db.movieDao(),
                        tvShowDao = db.tvShowDao(),
                        episodeDao = db.episodeDao(),
                        seasonDao = db.seasonDao(),
                        provider = provider
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${provider.name}: ${e.message}")
                    null
                }
            }
        )
    }

    inner class SyncServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.GET && session.uri == "/sync/ping" -> {
                    val json = JSONObject().apply {
                        put("deviceName", getDeviceName())
                        put("version", BuildConfig.VERSION_NAME)
                        put("localIp", getLocalIpForDisplay())
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }

                session.method == Method.GET && session.uri == "/sync/data" -> {
                    val data = try {
                        backupManager().exportUserData()
                    } catch (e: Exception) {
                        Log.e(TAG, "Export failed", e)
                        null
                    }
                    val json = data ?: JSONObject().apply { put("error", "export failed") }.toString()
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }

                session.method == Method.POST && session.uri == "/sync/data" -> {
                    val json = readBodyFromStream(session)

                    if (json != null && !isSyncing) {
                        isSyncing = true
                        try {
                            runBlocking {
                                backupManager().importUserData(json)
                            }
                            UserPreferences.lastSyncTimestamp = System.currentTimeMillis()
                            Log.i(TAG, "Received sync data from ${session.remoteIpAddress}")
                            newFixedLengthResponse(
                                Response.Status.OK,
                                "application/json",
                                """{"status":"ok"}"""
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Import failed on receive", e)
                            newFixedLengthResponse(
                                Response.Status.INTERNAL_ERROR,
                                "application/json",
                                """{"error":"${e.message}"}"""
                            )
                        } finally {
                            isSyncing = false
                        }
                    } else {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"status":"skipped"}"""
                        )
                    }
                }

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not found"
                )
            }
        }

        private fun readBodyFromStream(session: IHTTPSession): String? {
            return try {
                // NanoHTTPD reliably reads body via parseBody -> temp file
                val files = HashMap<String, String>()
                val parms = HashMap<String, String>()
                session.parseBody(files)
                val tempPath = files["postData"]
                if (tempPath != null) {
                    return java.io.File(tempPath).readText(Charsets.UTF_8)
                }
                // Fallback: direct stream read
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                val stream = session.inputStream ?: return null
                if (contentLength > 0) {
                    val bytes = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = stream.read(bytes, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(bytes, 0, totalRead, Charsets.UTF_8)
                } else {
                    stream.bufferedReader().use { it.readText() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading body stream", e)
                null
            }
        }
    }
}
