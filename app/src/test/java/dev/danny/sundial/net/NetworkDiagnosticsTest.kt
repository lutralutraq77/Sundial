package dev.danny.sundial.net

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NetworkDiagnostics.explain] is the part that decides what the user is told, so it is
 * pinned here. `capture` is not: it reads ConnectivityManager and calls socket(2), which
 * a JVM unit test cannot exercise honestly.
 */
class NetworkDiagnosticsTest {

    private fun diagnostics(
        socketError: String? = null,
        hasActiveNetwork: Boolean = true,
        validated: Boolean = true,
        vpn: Boolean = false,
        privateDnsActive: Boolean = false,
        privateDnsHost: String? = null,
        appWasForeground: Boolean = true,
    ) = NetworkDiagnostics(
        socketError = socketError,
        hasActiveNetwork = hasActiveNetwork,
        validated = validated,
        vpn = vpn,
        privateDnsActive = privateDnsActive,
        privateDnsHost = privateDnsHost,
        appWasForeground = appWasForeground,
    )

    @Test
    fun `says nothing when nothing stood out`() {
        assertNull(diagnostics().explain())
    }

    @Test
    fun `names the Network permission only when a socket was actually refused`() {
        val refused = diagnostics(socketError = "EACCES").explain().orEmpty()
        assertTrue(refused, refused.contains("Permissions -> Network"))

        // The whole point of the rewrite: a plain lookup failure on a healthy-looking
        // device must not accuse the permission.
        assertNull(diagnostics().explain())
    }

    @Test
    fun `reports every observation that held, not just the first`() {
        // These co-occur in the field, and a `when` would have reported only the
        // no-network line, hiding the Android 15 block that actually caused it.
        val both = diagnostics(hasActiveNetwork = false, appWasForeground = false)
            .explain().orEmpty()
        assertTrue(both, both.contains("no usable network"))
        assertTrue(both, both.contains("background"))
    }

    @Test
    fun `does not claim a captive portal when there is no network to read`() {
        // With no active network there are no capabilities, so `validated` is false for
        // want of an answer rather than because a portal is in the way.
        val offline = diagnostics(hasActiveNetwork = false, validated = false)
            .explain().orEmpty()
        assertTrue(offline, !offline.contains("captive portal"))

        val portal = diagnostics(hasActiveNetwork = true, validated = false)
            .explain().orEmpty()
        assertTrue(portal, portal.contains("captive portal"))
    }

    @Test
    fun `names the private DNS server when there is one`() {
        val named = diagnostics(privateDnsActive = true, privateDnsHost = "dns.example")
            .explain().orEmpty()
        assertTrue(named, named.contains("dns.example"))

        val auto = diagnostics(privateDnsActive = true).explain().orEmpty()
        assertTrue(auto, auto.contains("automatic"))
    }
}
