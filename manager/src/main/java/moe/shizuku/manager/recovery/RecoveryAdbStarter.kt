/* Optional TCP mode adapted from thedjchi/Shizuku (Apache-2.0). */
package moe.shizuku.manager.recovery

import kotlinx.coroutines.*
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.*
import moe.shizuku.manager.starter.Starter
import java.io.EOFException
import java.net.SocketException

object RecoveryAdbStarter {
    suspend fun start(host: String, port: Int, log: ((ByteArray) -> Unit)? = null) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        val tcp = RecoveryController.tcpEnabled()
        if (tcp && port != 5555) {
            AdbClient(host, port, key, 8_000).use { client ->
                client.connect()
                try { client.command("tcpip:5555") }
                catch (_: EOFException) { /* adbd closes the socket on restart */ }
                catch (_: SocketException) { /* adbd closes the socket on restart */ }
            }
            delay(1_500)
        }
        currentCoroutineContext().ensureActive()
        if (RecoveryController.paused()) throw CancellationException("Manually stopped")
        val activePort = if (tcp) 5555 else port
        AdbClient(host, activePort, key, 8_000).use { client ->
            client.connect()
            client.shellCommand(Starter.internalCommand, log)
        }
    }
}
