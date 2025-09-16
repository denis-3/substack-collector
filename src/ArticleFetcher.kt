package articlefetcher

import java.time.Duration
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.net.URI
import java.util.zip.GZIPInputStream
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class HttpRequestSummary(val statusCode: Short, val headers: Map<String, List<String>>, val text: String) {}

// A global logger class that can collect messages from the various scraping functions in memory
// Useful for the webserver to display output to the user
class LoggerClass(var preserveLogsInMemory: Boolean) {
	var log: String = ""

	fun addLog(msg: String) {
		if (preserveLogsInMemory) log += msg + "\n"
		println(msg)
	}
}

val Logger = LoggerClass(true)

val HTTP_HEADERS = mapOf(
	"User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
	"Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
	"Accept-Language" to "en-US,en;q=0.5",
	"Accept-Encoding" to "gzip",
	"Sec-GPC" to "1",
	"Upgrade-Insecure-Requests" to "1",
	"Sec-Fetch-Dest" to "document",
	"Sec-Fetch-Mode" to "navigate",
	"Sec-Fetch-Site" to "none",
	"Sec-Fetch-User" to "?1",
	"Priority" to "u=0, i",
	"TE" to "trailers"
)

// Basic implementation of an HTTP2 request
private fun http2Request(url: String): HttpRequestSummary {
	val cookieManager = CookieHandler.getDefault()
	if (cookieManager == null) {
		val newCookieManager = CookieManager()
		CookieHandler.setDefault(newCookieManager)
		newCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
	}
	val client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(60))
		.cookieHandler(CookieHandler.getDefault())
		.build()
	val request = HttpRequest.newBuilder()
		.uri(URI.create(url))

	HTTP_HEADERS.forEach { (headerName, value) ->
		request.header(headerName, value)
	}

	val resp = client.send(request.build(), BodyHandlers.ofInputStream())
	val respHeaders = resp.headers()
	val compression = respHeaders.firstValue("Content-Encoding").getOrNull()

	if (compression == "gzip") {
		val gUnzipInput = GZIPInputStream(resp.body())
		return HttpRequestSummary(
			resp.statusCode().toShort(),
			respHeaders.map(),
			gUnzipInput.bufferedReader().use { it.readText() }
		)
	} else if (compression != null) {
		throw Exception("Unrecognized compression: $compression")
	}

	return HttpRequestSummary(
		resp.statusCode().toShort(),
		respHeaders.map(),
		resp.body().bufferedReader().use { it.readText() }
	)
}

// Custom HTTP2 request with cookies and re-try support
// This will block so it must be called in, e.g., coroutineScope {}
suspend fun customHttp2Request(url: String, retry: Boolean = true): HttpRequestSummary {
	val httpResp = http2Request(url)
	if (httpResp.statusCode == 429.toShort() && retry) {
		Logger.addLog("Got a 429 status code! Retrying in 15s with new cookies...")
		delay(15000)
		print("After delay")
		// New cookies
		val cookieManager = CookieManager()
		CookieHandler.setDefault(cookieManager)
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
		return customHttp2Request(url, false)
	} else if (httpResp.statusCode != 200.toShort() && httpResp.statusCode != 403.toShort()) { // Some private substacks return 403
		Logger.addLog("Start HTTP response from failing request")
		Logger.addLog(httpResp.text)
		throw Exception("Unrecognized status code [$url]: ${httpResp.statusCode}")
	}
	return httpResp
}
