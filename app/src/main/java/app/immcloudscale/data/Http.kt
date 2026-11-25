package app.immcloudscale.data

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** How requests reach the home server: straight there, or through Tailscale's local proxy when away. */
interface HomeRoute {
    /** The proxy to use for the home server right now, or null to go direct. */
    fun proxy(): Proxy?
    /** Sent to the proxy so other apps on the phone can't use it. */
    fun token(): String
    /** More server addresses to route, e.g. while joining through an invite (not paired yet). */
    fun extraHosts(): List<String> = emptyList()
}

/** Sends only the paired server's addresses through [route]; everything else goes direct. */
class HomeProxySelector(private val settings: () -> AppSettings, private val route: HomeRoute) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        val p = route.proxy() ?: return listOf(Proxy.NO_PROXY)
        val s = settings()
        val hosts = (listOf(s.pairedServer, s.immichUrl, s.ncUrl) + route.extraHosts()).mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }
        return if (uri.host?.lowercase() in hosts) listOf(p) else listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/**
 * One OkHttp client for the API calls, Coil thumbnails and ExoPlayer streams. The interceptor adds
 * the right credentials depending on which server a request goes to, so image URLs can stay plain.
 */
class AuthInterceptor(
    private val settings: () -> AppSettings,
    private val guard: HomeGuard? = null,
    private val route: HomeRoute? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var req = chain.request()
        // This connection itself goes through Tailscale's proxy (not just "Tailscale is on": a pooled direct
        // connection can outlive a network change).
        val viaTailscale = route != null && chain.connection()?.route()?.proxy?.type() == Proxy.Type.HTTP
        if (viaTailscale) req = req.newBuilder().header("Proxy-Authorization", "Bearer ${route!!.token()}").build()
        // Every request to the paired server's addresses is checked, also those that bring their own credentials
        // (members management, the locked folder): they carry secrets too.
        guard?.check(req.url, viaTailscale)
        if (req.header("Authorization") != null || req.header("x-api-key") != null) return chain.proceed(req)
        val s = settings()
        val url = req.url
        val b = req.newBuilder()
        // Both servers can sit behind one reverse proxy (nas.lan/ and nas.lan/nextcloud): the longer base path wins.
        val immich = if (s.immichConfigured) matchLength(url.toString(), s.immichUrl) else -1
        val nc = if (s.nextcloudConfigured) matchLength(url.toString(), s.ncUrl) else -1
        if (immich >= 0 && immich >= nc) {
            if (s.immichTokenIsApiKey) b.header("x-api-key", s.immichToken)
            else b.header("Authorization", "Bearer ${s.immichToken}")
        } else if (nc >= 0) {
            b.header("Authorization", Credentials.basic(s.ncUser, s.ncAppPassword))
            b.header("OCS-APIRequest", "true")
        }
        return chain.proceed(b.build())
    }

    /** Length of [base]'s path when [url] is under it (same scheme, host and port), else -1. */
    private fun matchLength(url: String, base: String): Int {
        val ua = url.toHttpUrlOrNull() ?: return -1
        val ub = base.toHttpUrlOrNull() ?: return -1
        val prefix = ub.encodedPath.trimEnd('/')
        val path = ua.encodedPath
        val under = path == prefix || path.startsWith("$prefix/")
        return if (ua.scheme == ub.scheme && ua.host == ub.host && ua.port == ub.port && under) prefix.length else -1
    }
}

// A network interceptor runs for every hop, so credentials are decided again after each redirect and never
// follow a redirect to another host.
fun buildHttpClient(settings: () -> AppSettings, guard: HomeGuard? = null, route: HomeRoute? = null): OkHttpClient = OkHttpClient.Builder()
    .addNetworkInterceptor(AuthInterceptor(settings, guard, route))
    .apply { if (route != null) proxySelector(HomeProxySelector(settings, route)) }
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.MINUTES)
    .build()

class HttpException(val code: Int, message: String) : Exception(message)
