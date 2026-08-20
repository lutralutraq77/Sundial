package dev.danny.sundial.auth

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * One-shot HTTP server bound to 127.0.0.1 that catches the OAuth redirect.
 *
 * Using a loopback redirect instead of a custom URI scheme means the client ID is not
 * baked into the APK at build time — it can be entered at runtime, and the same build
 * works with any Google Cloud project.
 *
 * The port is reachable by every local app with the INTERNET permission, and browsers
 * open speculative connections that never send a request. So only a request that
 * carries the OAuth outcome for [expectedState] may end the wait — anything else is
 * answered and the server keeps listening.
 */
class LoopbackServer(private val expectedState: String? = null) : Closeable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val port: Int = server.localPort

    val redirectUri: String = "http://127.0.0.1:$port"

    /**
     * Blocks until the browser requests the redirect URI with an OAuth response and
     * returns its query parameters. Throws when [close] is called first, which is how
     * cancellation works.
     */
    fun awaitRedirect(): Map<String, String> {
        while (true) {
            val socket = server.accept()
            try {
                // A connection that sends nothing (browser preconnect) must not block
                // the real redirect behind it in the accept queue.
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: continue
                val target = requestLine.split(' ').getOrNull(1) ?: "/"
                val params = parseQuery(target)

                // Only Google's redirect for THIS attempt ends the wait: it carries a
                // code or an error, and (when a state is expected) the matching state.
                // Favicon probes, stray requests and other local apps get a 404 —
                // they must not consume the one-shot server. A process that does not
                // know the state value can never terminate the flow.
                val isRedirect = (params.containsKey("code") || params.containsKey("error")) &&
                    (expectedState == null || params["state"] == expectedState)
                if (!isRedirect) {
                    runCatching { write(socket, "404 Not Found", "text/plain", "") }
                    continue
                }

                val page = if (params.containsKey("code")) successPage() else errorPage(params)
                // The params are already in hand — a dead socket (the browser gave up
                // while this process was frozen) must not turn Google's approval into
                // a failed sign-in. The confirmation page is best-effort.
                runCatching { write(socket, "200 OK", "text/html; charset=utf-8", page) }
                return params
            } catch (_: IOException) {
                // Idle connection timing out, or a peer that aborted with RST mid-read
                // (a cancelled browser preconnect, a port scanner, any local app): a
                // bad client connection must never end the wait — that is the same
                // stray-request hole the code/state filtering closes. Only accept()
                // failing (our own socket closed = cancellation) may throw out.
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun write(socket: Socket, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun parseQuery(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            runCatching {
                URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    // This page is served by the loopback server the instant Google redirects
    // back -- BEFORE the token exchange runs. Claiming access here is a lie
    // whenever that exchange then fails (no network permission, blocked DNS),
    // which sends the user back to the sign-in screen with no explanation.
    // State only what has actually happened: Google returned the code.
    private fun successPage(): String = page(
        title = "Authorised",
        heading = "Google approved Sundial",
        detail = "Go back to the app to finish signing in. If it returns to the sign-in " +
            "screen, the app could not reach Google to complete the exchange, and the " +
            "reason is shown there.",
    )

    private fun errorPage(params: Map<String, String>): String {
        val reason = params["error_description"] ?: params["error"] ?: "No authorisation code was returned."
        return page(
            title = "Sign-in failed",
            heading = "Sign-in didn't complete",
            detail = escapeHtml(reason),
        )
    }

    private fun page(title: String, heading: String, detail: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          :root { color-scheme: light dark; }
          body { font-family: system-ui, sans-serif; margin: 0; min-height: 100vh;
                 display: flex; align-items: center; justify-content: center; padding: 24px; }
          .card { max-width: 26rem; text-align: center; }
          h1 { font-size: 1.4rem; margin: 0 0 .5rem; }
          p { margin: 0; opacity: .75; line-height: 1.5; }
          a.btn { display: inline-block; margin-top: 1.5rem; padding: .7rem 1.4rem;
                  border-radius: 999px; text-decoration: none; font-weight: 600;
                  background: #b9c3ff; color: #12121a; }
          a.btn:active { opacity: .8; }
        </style></head>
        <body><div class="card">
          <h1>$heading</h1><p>$detail</p>
          <!-- An intent: URL reopens the app's existing task (MainActivity is
               singleTask), which window.close() cannot do for a tab the user
               navigated to rather than one script opened. -->
          <a class="btn" id="back" href="$RETURN_INTENT_URI">Return to Sundial</a>
        </div>
        <script>
          document.getElementById('back').addEventListener('click', function () {
            // Give the intent a moment to fire, then close this tab if the
            // browser allows it. Harmless when it does not.
            setTimeout(function () { window.close(); }, 600);
          });
        </script>
        </body></html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        /** Handled by MainActivity's sundial:// intent filter. */
        const val RETURN_INTENT_URI =
            "intent://returned#Intent;scheme=sundial;package=dev.danny.sundial;end"

        const val SOCKET_READ_TIMEOUT_MS = 3_000
    }
}
