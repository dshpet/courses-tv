package io.github.dshpet.coursestv

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object Hosts {
    const val SITE = "https://plus.thegreatcourses.com"
    const val API = "https://m2api.thegreatcourses.com"
    const val IMAGES = "https://secureimages.teach12.com/tgc/images/m2/wondrium/"
    const val STREAMS = "https://link.theplatform.com/s/jESqeC/media/guid/2661884195/"

    fun isSite(url: String?): Boolean = url?.startsWith("$SITE/") == true
}

/** The API answered 401: the website session has ended. */
class SignedOutException : IOException("signed out")

/** [runCatching] that lets cancellation through. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** The service's JSON API, called as the website calls it: the site's `global_auth_token` cookie is the Bearer token. */
class Api(client: OkHttpClient) {

    private val http = client.newBuilder().cookieJar(WebViewCookies).build()

    /** A JSON object's text; anything else (a proxy's HTML page) is an error. */
    suspend fun get(url: String): String = call(Request.Builder().url(url)).also { if (!it.startsWith("{")) throw IOException("not JSON") }

    suspend fun post(url: String, body: JSONObject) = write(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)), body)

    suspend fun delete(url: String) = write(Request.Builder().url(url).delete())

    // Debug and read-only builds change nothing on the account.
    private suspend fun write(request: Request.Builder, body: JSONObject? = null) {
        if (BuildConfig.WRITES) {
            call(request)
        } else {
            val sent = request.build()
            Log.i(TAG, "Not sent (read-only build): ${sent.method} ${sent.url} ${body ?: ""}")
        }
    }

    private suspend fun call(request: Request.Builder): String = withContext(Dispatchers.IO) {
        WebViewCookies.token?.let { request.header("Authorization", "Bearer $it") }
        http.newCall(request.header("Accept", "application/json").build()).execute().use { response ->
            if (response.code == 401) throw SignedOutException()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string().trim()
        }
    }

    private companion object {
        const val TAG = "Api"
        val JSON = "application/json".toMediaType()
    }
}

/** The WebView's cookies, sent with native requests; read-only, the website alone sets them. */
object WebViewCookies : CookieJar {

    val token: String?
        get() = cookies(Hosts.SITE).firstOrNull { it.startsWith("global_auth_token=") }?.substringAfter('=')?.ifEmpty { null }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies(url.toString()).mapNotNull { Cookie.parse(url, it) }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

    /** Signs out of the website: its session lives only in these cookies and its storage. */
    fun clear(done: () -> Unit) {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }

    private fun cookies(url: String): List<String> =
        CookieManager.getInstance().getCookie(url)?.split(";")?.map { it.trim() }.orEmpty()
}
