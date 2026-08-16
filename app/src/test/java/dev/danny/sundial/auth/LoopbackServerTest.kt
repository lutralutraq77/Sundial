package dev.danny.sundial.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
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
            // Was asserting "signed in", which the page has never said -- it says
            // "signing in", deliberately, because at this point nothing has been
            // exchanged yet. The assertion passed no build.
            assertTrue(body, body.contains("finish signing in", ignoreCase = true))

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
    fun `success page returns to the app automatically, failure page does not`() {
        // The token exchange cannot run until Sundial is visible again, so the success
        // page has to hand the foreground back without waiting for a tap. The failure
        // page must not, or the reason scrolls past before it can be read.
        val granted = LoopbackServer()
        val denied = LoopbackServer()
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit<Map<String, String>> { granted.awaitRedirect() }
            pool.submit<Map<String, String>> { denied.awaitRedirect() }

            val (_, successBody) = hit("${granted.redirectUri}/?code=abc&state=xyz")
            val (_, errorBody) = hit("${denied.redirectUri}/?error=access_denied")

            assertTrue(successBody, successBody.contains("http-equiv=\"refresh\""))
            assertTrue(successBody, successBody.contains("scheme=sundial"))
            assertTrue(errorBody, !errorBody.contains("http-equiv=\"refresh\""))
            // The manual fallback survives on both, since browsers may refuse to
            // follow an intent: URL without a user gesture.
            assertTrue(errorBody, errorBody.contains("Return to Sundial"))
        } finally {
            granted.close()
            denied.close()
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
            val (_, body) = hit("${server.redirectUri}/?error=x&error_description=%3Cscript%3E")
            assertTrue(body, body.contains("&lt;script&gt;"))
            assertTrue(body, !body.contains("<script>"))
        } finally {
            server.close()
            pool.shutdownNow()
        }
    }
}
