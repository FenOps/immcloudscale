package app.immcloudscale.data

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * One OkHttp client for the API calls, Coil thumbnails and ExoPlayer streams. The interceptor adds
 * the right credentials depending on which server a request goes to, so image URLs can stay plain.
 */
class AuthInterceptor(private val settings: () -> AppSettings, private val guard: HomeGuard? = null) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") != null || req.header("x-api-key") != null) return chain.proceed(req)
        val s = settings()
        val url = req.url
        val b = req.newBuilder()
        // Both servers can sit behind one reverse proxy (nas.lan/ and nas.lan/nextcloud): the longer base path wins.
        val immich = if (s.immichConfigured) matchLength(url.toString(), s.immichUrl) else -1
        val nc = if (s.nextcloudConfigured) matchLength(url.toString(), s.ncUrl) else -1
        if ((immich >= 0 || nc >= 0)) guard?.check(url)
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
fun buildHttpClient(settings: () -> AppSettings, guard: HomeGuard? = null): OkHttpClient = OkHttpClient.Builder()
    .addNetworkInterceptor(AuthInterceptor(settings, guard))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.MINUTES)
    .build()

class HttpException(val code: Int, message: String) : Exception(message)
