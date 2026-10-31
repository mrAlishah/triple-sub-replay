package com.kienhoang.dualsubreplay.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

private const val MAX_REQUEST_LINE = 8 * 1024
private const val READ_TIMEOUT_MS = 5_000
private const val ACCEPT_SLICE_MS = 500L

/**
 * The one-shot `http://127.0.0.1:<port>/auth/callback` listener the preview's sign-in needs. It binds to the
 * loopback address only (never the LAN), takes an OS-chosen port, answers anything but the real callback with
 * an error and keeps waiting, and closes after the first good callback, [close] or the timeout. It is a plain
 * socket, so Android's cleartext policy stays off for every other connection.
 */
internal class ChatGptLoopback : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    val port: Int get() = server.localPort

    /** Waits for the callback that carries [expectedState]; returns its result, or throws when [timeoutMs] runs out. */
    suspend fun await(
        expectedState: String,
        timeoutMs: Long,
        now: () -> Long = System::currentTimeMillis,
    ): ChatGptCallback =
        withContext(Dispatchers.IO) {
            val deadline = now() + timeoutMs
            var result: ChatGptCallback? = null
            while (result == null) {
                ensureActive()
                val left = deadline - now()
                if (left <= 0) throw ChatGptException(ChatGptErrorKind.SIGN_IN_FAILED, "The ChatGPT sign-in timed out.")
                // Short slices, so cancelling the sign-in closes the wait within half a second.
                server.soTimeout = minOf(left, ACCEPT_SLICE_MS).toInt()
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                result = socket.use { answer(it, expectedState) }.takeIf { it !is ChatGptCallback.Invalid }
            }
            result
        }

    private fun answer(
        socket: Socket,
        expectedState: String,
    ): ChatGptCallback {
        // A client that never sends its request line must not hold the sign-in up.
        socket.soTimeout = READ_TIMEOUT_MS
        val line =
            try {
                readRequestLine(socket)
            } catch (_: IOException) {
                return ChatGptCallback.Invalid("Unreadable request.")
            }
        val result = parseChatGptCallback(line, expectedState)
        val (status, text) =
            when (result) {
                is ChatGptCallback.Code -> "200 OK" to "You are signed in. You can return to the app."
                ChatGptCallback.Denied -> "200 OK" to "Sign-in was cancelled. You can return to the app."
                is ChatGptCallback.Invalid -> "400 Bad Request" to "This is not the sign-in answer."
            }
        try {
            val body =
                "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
                    "<title>TripleSub Replay</title><p>$text</p>"
            val bytes = body.toByteArray()
            socket.getOutputStream().apply {
                val headers =
                    listOf(
                        "HTTP/1.1 $status",
                        "Content-Type: text/html; charset=utf-8",
                        "Content-Length: ${bytes.size}",
                        "Cache-Control: no-store",
                        "Connection: close",
                    )
                write((headers.joinToString("\r\n") + "\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        } catch (_: IOException) {
            // The browser went away; the callback itself was already read.
        }
        return result
    }

    private fun readRequestLine(socket: Socket): String {
        val input = socket.getInputStream()
        val line = StringBuilder()
        // One total deadline, so a client that trickles bytes cannot hold the sign-in up.
        val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000L
        while (line.length < MAX_REQUEST_LINE) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw SocketTimeoutException("The request was too slow.")
            socket.soTimeout = left.toInt()
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            line.append(next.toChar())
        }
        return line.toString().trimEnd('\r')
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }
}
