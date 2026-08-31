package com.pihub.android

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PiClient {
    private var session: Session? = null

    suspend fun connect(host: String, port: Int, user: String, password: String) = withContext(Dispatchers.IO) {
        disconnect()
        val s = JSch().getSession(user, host, port)
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(8000)
        session = s
    }

    suspend fun exec(command: String, timeoutMs: Long = 7000): String = withContext(Dispatchers.IO) {
        val s = session ?: error("Not connected")
        val channel = s.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null
        val output = channel.inputStream
        channel.connect(4000)
        val buffer = ByteArray(8192)
        val result = StringBuilder()
        val started = System.currentTimeMillis()
        while (true) {
            while (output.available() > 0) {
                val n = output.read(buffer, 0, buffer.size)
                if (n > 0) result.append(String(buffer, 0, n))
            }
            if (channel.isClosed) break
            if (System.currentTimeMillis() - started > timeoutMs) {
                channel.disconnect()
                error("Command timed out")
            }
            Thread.sleep(30)
        }
        channel.disconnect()
        result.toString().trim()
    }

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    fun isConnected() = session?.isConnected == true
}
