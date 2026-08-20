package dev.danny.sundial.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The loopback redirect is the piece that makes Play-Services-free OAuth work, so it is
 * exercised for real here: a live socket, a real HTTP GET, and the parsed result.
 */
class LoopbackServerTest {

    private fun hit(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val code = connection.responseCode
            val body = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `captures the authorization code from the redirect`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            val (status, body) = hit("${server.redirectUri}/?code=4%2F0Axyz&state=abc123&scope=calendar")

            assertEquals(200, status)
            // The page deliberately does NOT claim the user is signed in — the token
            // exchange has not run yet. It states what actually happened: approval.
            assertTrue(body, body.contains("approved", ignoreCase = true))

            val params = redirect.get(5, TimeUnit.SECONDS)
            assertEquals("4/0Axyz", params["code"])
            assertEquals("abc123", params["state"])
            assertEquals("calendar", params["scope"])
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `binds only to loopback`() {
        val server = LoopbackServer()
        try {
            assertTrue(server.redirectUri, server.redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(server.port in 1024..65535)
        } finally {
            server.close()
        }
    }

    @Test
    fun `surfaces a denial from the consent screen`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            val (status, body) = hit(
                "${server.redirectUri}/?error=access_denied&error_description=The+user+refused",
            )

            assertEquals(200, status)
            assertTrue(body, body.contains("didn't complete", ignoreCase = true))

            val params = redirect.get(5, TimeUnit.SECONDS)
            assertEquals("access_denied", params["error"])
            assertEquals("The user refused", params["error_description"])
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `ignores a favicon request and keeps waiting for the real redirect`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            // Browsers routinely ask for /favicon.ico first; that must not be mistaken
            // for the redirect and end the flow empty-handed.
            hit("${server.redirectUri}/favicon.ico")
            hit("${server.redirectUri}/?code=second-try&state=s")

            val params = redirect.get(5, TimeUnit.SECONDS)
            assertEquals("second-try", params["code"])
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a request without an OAuth response cannot consume the server`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            // The port is reachable by any local process. A query-less probe (or any
            // request without code/error) used to permanently end the one-shot wait
            // and fail sign-in with a misleading state-mismatch error.
            val (status, _) = hit("${server.redirectUri}/")
            assertEquals(404, status)
            hit("${server.redirectUri}/robots.txt")

            hit("${server.redirectUri}/?code=real&state=s")
            val params = redirect.get(5, TimeUnit.SECONDS)
            assertEquals("real", params["code"])
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a redirect with the wrong state is rejected when a state is expected`() {
        val server = LoopbackServer(expectedState = "expected-state")
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            // A local process that does not know the state value must not be able to
            // terminate the flow, with or without a fake code.
            val (status, _) = hit("${server.redirectUri}/?code=fake&state=wrong")
            assertEquals(404, status)

            hit("${server.redirectUri}/?code=genuine&state=expected-state")
            val params = redirect.get(5, TimeUnit.SECONDS)
            assertEquals("genuine", params["code"])
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `an idle connection does not block the real redirect`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit<Map<String, String>> { server.awaitRedirect() }

            // Chromium-family browsers open speculative connections that send nothing.
            // The server must time that socket out rather than wait on it forever
            // while the genuine redirect sits in the accept queue.
            Socket("127.0.0.1", server.port).use {
                hit("${server.redirectUri}/?code=after-idle&state=s")
                val params = redirect.get(10, TimeUnit.SECONDS)
                assertEquals("after-idle", params["code"])
            }
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `close unblocks a waiting redirect`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val redirect = pool.submit { server.awaitRedirect() }
            server.close()
            // Cancelling sign-in works by closing the socket; accept() must not hang.
            try {
                redirect.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // An ExecutionException wrapping SocketException is the expected outcome.
            }
            assertTrue(redirect.isDone)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `escapes error text before putting it in the page`() {
        val server = LoopbackServer()
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit<Map<String, String>> { server.awaitRedirect() }
            // The page ships its own legitimate <script> block for the return button,
            // so the assertion targets the injected payload specifically.
            val (_, body) = hit(
                "${server.redirectUri}/?error=x&error_description=%3Cscript%3Ealert(1)%3C%2Fscript%3E",
            )
            assertTrue(body, body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
            assertTrue(body, !body.contains("<script>alert"))
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }
}
