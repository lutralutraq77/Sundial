package dev.danny.sundial.auth

import java.io.BufferedReader
import java.io.Closeable
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
 */
class LoopbackServer : Closeable {

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val port: Int = server.localPort

    val redirectUri: String = "http://127.0.0.1:$port"

    /**
     * Blocks until the browser requests the redirect URI and returns its query
     * parameters. Throws when [close] is called first, which is how cancellation works.
     */
    fun awaitRedirect(): Map<String, String> {
        while (true) {
            val socket = server.accept()
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: continue
                val target = requestLine.split(' ').getOrNull(1) ?: "/"
                if (target.startsWith("/favicon")) {
                    write(socket, "404 Not Found", "text/plain", "")
                    continue
                }
                val params = parseQuery(target)
                val page = if (params.containsKey("code")) successPage() else errorPage(params)
                write(socket, "200 OK", "text/html; charset=utf-8", page)
                return params
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
    }
}
