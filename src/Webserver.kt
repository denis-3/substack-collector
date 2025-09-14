import articlefetcher.Logger
import substacklogic.downloadSelectedCategories
import substacklogic.scrapeArticle
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

// buffer size for reading HTTP body
private val BUFFER_SIZE = 1024
// 10 KiB max request size
private val MAX_HTTP_BODY_SIZE = 10 * 1024
// Global handle on the scraping job
private var SCRAPING_JOB: Job? = null

private fun String.toIntOrZero() = this.toIntOrNull() ?: 0

// Useful for debugging
fun printCurrentThread() {
	println("In thread ${Thread.currentThread().name}")
}

// Finish this HTTP exchange by sending a string
private fun HttpExchange.sendStringAndClose(statusCode: Int, headers: Map<String, String>, content: String) {
	if (!headers.isEmpty()) {
		val respHeaders = this.responseHeaders
		headers.forEach { (key, value) ->
			respHeaders.add(key, value)
		}
	}
	if (content.isEmpty()) {
		this.sendResponseHeaders(statusCode, -1L)
	} else {
		this.sendResponseHeaders(statusCode, content.length.toLong())
		val outStream = this.responseBody
		outStream.write(content.toByteArray())
		outStream.close()
	}
}

class HomePageHandler() : HttpHandler {
	override fun handle(exchange: HttpExchange) = runBlocking {
		val fullURI = exchange.requestURI.toString()
		// full URI is the absolute path including any query arguments
		val path = if (fullURI.contains("?")) fullURI.split("?")[0] else fullURI
		val reqHeaders = exchange.requestHeaders
		val reqContentLength = reqHeaders.get("Content-Length") ?: listOf()
		if (reqContentLength.size > 1 || (reqContentLength.size == 1 && reqContentLength[0].toIntOrZero() >= MAX_HTTP_BODY_SIZE)) {
			return@runBlocking exchange.sendStringAndClose(413, mapOf("Content-Type" to "text/plain; charset=UTF-8"), "Request body larger than 10 KiB")
		}

		var reqBody = ""
		val reqBodyStream = exchange.requestBody
		val buffer = ByteArray(BUFFER_SIZE)
		var totalSize: Int = 0
		while (reqBodyStream.read(buffer).also{ totalSize += it } != -1) {
			if (totalSize >= MAX_HTTP_BODY_SIZE) {
				return@runBlocking exchange.sendStringAndClose(413,
					mapOf("Content-Type" to "text/plain; charset=UTF-8"),
					"Request body larger than 10 KiB")
			}
			reqBody += buffer.toString(Charsets.UTF_8)
		}

		reqBody = reqBody.slice(0..totalSize)

		val reqMethod = exchange.requestMethod
		println("got a request: $path")
		when (reqMethod) {
			"GET" -> {
				when (path) {
					"/" -> {
						val indexFile = File("./web/index.html").bufferedReader().use { it.readText() }
						return@runBlocking exchange.sendStringAndClose(200,
							mapOf("Content-Type" to "text/html; charset=UTF-8"),
							indexFile)
					}
					"/categories" -> {
						val indexFile = File("./categories.txt").bufferedReader().use { it.readText() }
						return@runBlocking exchange.sendStringAndClose(200,
							mapOf("Content-Type" to "text/html; charset=UTF-8"),
							indexFile)
					}
					"/get-logs" -> {
						return@runBlocking exchange.sendStringAndClose(200,
							mapOf("Content-Type" to "text/plain; charset=UTF-8"),
							Logger.log)
					}
				}
			}
			"PUT" -> {
				if (path == "/categories") {
					File("./categories.txt").bufferedWriter().use { out -> out.write(reqBody) }
					return@runBlocking exchange.sendStringAndClose(204, mapOf(), "")
				}
			}
			"POST" -> {
				if (path == "/start-scraping") {
					if (SCRAPING_JOB != null && !SCRAPING_JOB!!.isCompleted) {
						return@runBlocking exchange.sendStringAndClose(409,
							mapOf("Content-Type" to "text/html; charset=UTF-8"),
							"Already scraping!")
					}
					SCRAPING_JOB = launch { downloadSelectedCategories() }
					return@runBlocking exchange.sendStringAndClose(204, mapOf(), "")
				}
			}
		}

		exchange.sendStringAndClose(404, mapOf("Content-Type" to "text/plain; charset=UTF-8"), "Method $reqMethod not found for $path\n")
	}
}

fun main() {
	// Start the HTTP Server
	val httpServer = HttpServer.create(InetSocketAddress(6237), 0) // 0 to reject backlogged connection
	httpServer.createContext("/", HomePageHandler())
	httpServer.setExecutor(
		// Set the amount of threads to use for handling web requests
		Executors.newFixedThreadPool(3)
	)
	httpServer.start()
	println("HTTP server running on port 6237")
}
