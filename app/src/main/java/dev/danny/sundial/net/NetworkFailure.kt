package dev.danny.sundial.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Describes a network failure in terms of what was actually observed.
 *
 * The wording this replaces asserted a cause nothing had checked: every
 * UnknownHostException was reported as "this app has no network access", pointing the
 * user at the GrapheneOS Network toggle. That is the one explanation the failure could
 * not have had. Sign-in opens a ServerSocket on 127.0.0.1 before it ever talks to
 * Google, and GrapheneOS guards loopback with the same permission -- "The device-local
 * network (localhost) is also guarded by this permission" (grapheneos.org/features) --
 * so a revoked toggle fails earlier, in LoopbackServer, with a different message.
 *
 * A denied Network permission does not surface as UnknownHostException either. It
 * surfaces as EACCES/EPERM from socket(2), which the old `when` dropped into its
 * `else` branch and printed raw. The accusation was attached to the one exception type
 * that rules it out, and withheld from the one that proves it.
 */
object NetworkFailure {

    /**
     * A one-line statement of the failure, with the resolver's own words kept intact.
     * Pair it with [NetworkDiagnostics.explain] for the likely cause.
     */
    fun describe(t: Throwable): String {
        val errno = errnoOf(t)
        return when {
            errno == OsConstants.EACCES || errno == OsConstants.EPERM ->
                "The system refused Sundial a network socket (${errnoName(errno)})."
            t is UnknownHostException ->
                "Could not resolve Google's address" + t.message.asDetail()
            t is ConnectException || t is NoRouteToHostException ->
                "Could not connect to Google" + t.message.asDetail()
            t is SocketTimeoutException -> "Google took too long to answer."
            t is SSLException ->
                "Secure connection to Google failed" + t.message.asDetail()
            else -> t.message ?: t::class.java.simpleName
        }
    }

    /** The errno of the first [ErrnoException] in the cause chain, if any. */
    fun errnoOf(t: Throwable): Int? {
        var current: Throwable? = t
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is ErrnoException) return current.errno
            current = current.cause
        }
        return null
    }

    private fun errnoName(errno: Int?): String =
        errno?.let { OsConstants.errnoName(it) } ?: "unknown"

    private fun String?.asDetail(): String =
        if (isNullOrBlank()) "." else ": $this"
}

/**
 * What the system said about the network at the moment a request failed.
 *
 * Every field here is measured rather than assumed, which is the whole point: the
 * permission hint is only ever shown when a socket really was refused.
 */
data class NetworkDiagnostics(
    val socketError: String?,
    val hasActiveNetwork: Boolean,
    val validated: Boolean,
    val vpn: Boolean,
    val privateDnsActive: Boolean,
    val privateDnsHost: String?,
    val appWasForeground: Boolean,
) {

    /** The most likely cause, or null when nothing measurable stood out. */
    fun explain(): String? = when {
        socketError != null ->
            "Sundial cannot open network sockets ($socketError) -- check Settings -> " +
                "Apps -> Sundial -> Permissions -> Network."

        // GrapheneOS documents exactly this shape for a denied Network permission:
        // "When the Network permission is disabled, GrapheneOS pretends the network is
        // down. It shows the network as down in various APIs, returns errors showing a
        // network connectivity issue rather than a revoked permission." An always-on
        // VPN in lockdown mode looks the same from here, so name both.
        !hasActiveNetwork ->
            "The system reports no usable network for Sundial, though the device appears " +
                "online. That is how a denied Network permission and an always-on VPN in " +
                "lockdown mode both look from inside the app."

        // Android 15 fails requests started "outside of a valid process lifecycle" with
        // an UnknownHostException, which is indistinguishable from real DNS trouble
        // unless we record where the app was at the time.
        !appWasForeground ->
            "Sundial was in the background when the request ran. Android 15 blocks " +
                "background network requests and reports them as DNS failures."

        privateDnsActive ->
            "Private DNS is active (${privateDnsHost ?: "automatic"}); if that resolver " +
                "is unreachable, every lookup fails."

        vpn -> "A VPN is active and may be routing or blocking this app's traffic."

        !validated ->
            "The current network is not validated -- a captive portal may need signing in to."

        else -> null
    }

    companion object {

        fun capture(context: Context, appWasForeground: Boolean): NetworkDiagnostics {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            val link = network?.let { connectivity.getLinkProperties(it) }
            return NetworkDiagnostics(
                socketError = probeSocket(),
                hasActiveNetwork = network != null,
                validated = capabilities
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                privateDnsActive = link?.isPrivateDnsActive == true,
                privateDnsHost = link?.privateDnsServerName,
                appWasForeground = appWasForeground,
            )
        }

        /**
         * Asks the kernel for a TCP socket and reports why it was refused.
         *
         * Deliberately not Context.checkSelfPermission(INTERNET): GrapheneOS spoofs the
         * self-check for its special runtime permissions, so an app that has had Network
         * revoked is still told it holds INTERNET. Gating on that would swap one
         * confident lie for another. The syscall cannot be spoofed the same way.
         *
         * Note the asymmetry -- a refusal proves the permission is denied, but success
         * proves nothing, since enforcement may sit further out at the egress layer.
         * That is why [explain] treats this as the strongest signal and never as the
         * only one.
         */
        private fun probeSocket(): String? {
            var fd: FileDescriptor? = null
            return try {
                fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                null
            } catch (e: ErrnoException) {
                // Only a refusal counts. Any other errno means the probe itself went
                // wrong, and reporting that as a permission problem would repeat the
                // mistake this class exists to correct.
                if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM) {
                    OsConstants.errnoName(e.errno) ?: "refused"
                } else {
                    null
                }
            } catch (t: Throwable) {
                null
            } finally {
                fd?.let { runCatching { Os.close(it) } }
            }
        }
    }
}
