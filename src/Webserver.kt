import coroutineexecutor.CoroutineExecutor
import articlefetcher.Logger
import substacklogic.downloadSelectedCategories
import substacklogic.getTopArticlesByKeyword
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking

// Map of paths to files and content-type (only supports GET)
private val FILE_PATHS_MAP = mapOf<String, List<String>>(
	"/" to listOf("resources/web/index.html", "text/html; charset=UTF-8"),
	"/config" to listOf("resources/web/config.html", "text/html; charset=UTF-8"),
	"/categories" to listOf("resources/categories.txt", "text/plain; charset=UTF-8"),
	"/settings.svg" to listOf("resources/web/settings.svg", "image/svg+xml")
)
// buffer size for reading HTTP body
private val BUFFER_SIZE = 1024
// 10 KiB max request size
private val MAX_HTTP_BODY_SIZE = 10 * 1024
// Global handle on the scraping job
private var SCRAPING_NOW = false

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
	fun _handle(exchange: HttpExchange) {
		val fullURI = exchange.requestURI.toString()
		// full URI is the absolute path including any query arguments
		val path = if (fullURI.contains("?")) fullURI.split("?")[0] else fullURI
		val reqHeaders = exchange.requestHeaders
		val reqContentLength = reqHeaders.get("Content-Length") ?: listOf()
		if (reqContentLength.size > 1 || (reqContentLength.size == 1 && reqContentLength[0].toIntOrZero() >= MAX_HTTP_BODY_SIZE)) {
			return exchange.sendStringAndClose(413, mapOf("Content-Type" to "text/plain; charset=UTF-8"), "Request body larger than 10 KiB")
		}

		var reqBody = ""
		val reqBodyStream = exchange.requestBody
		val buffer = ByteArray(BUFFER_SIZE)
		var totalSize: Int = 0
		while (reqBodyStream.read(buffer).also{ totalSize += it } != -1) {
			if (totalSize >= MAX_HTTP_BODY_SIZE) {
				return exchange.sendStringAndClose(413,
					mapOf("Content-Type" to "text/plain; charset=UTF-8"),
					"Request body larger than 10 KiB")
			}
			reqBody += buffer.toString(Charsets.UTF_8)
		}

		reqBody = reqBody.slice(0..totalSize)

		val reqMethod = exchange.requestMethod
		when (reqMethod) {
			"GET" -> {
				if (path in FILE_PATHS_MAP) {
					val (fileName, contentType) = FILE_PATHS_MAP[path]!!
					val targetFile = File(fileName).bufferedReader().use { it.readText() }
					return exchange.sendStringAndClose(200,
						mapOf("Content-Type" to contentType),
						targetFile)
				} else if (path == "/logs") {
					if (!SCRAPING_NOW) {
						Logger.log = ""
						// No logs for no job
						return exchange.sendStringAndClose(404, mapOf(), "")
					}
					// The first line of logs is the system status
					val headerStr = (if (SCRAPING_NOW) "Scraping" else "Idle") + "\n"
					return exchange.sendStringAndClose(200,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						headerStr + Logger.log)
				} else if (path == "/keyword-search") {
					if (fullURI.contains("&")) {
						return exchange.sendStringAndClose(400,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						"Can't have more than one query param\n")
					} else if (!fullURI.startsWith("/keyword-search?kws=")) {
						return exchange.sendStringAndClose(400,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						"Must have kws query param as a comma-separated list of keywords\n")
					}
					val startTime = System.currentTimeMillis()
					val searchResult: List<Pair<Double, String>> = runBlocking {
						getTopArticlesByKeyword(fullURI.split("?kws")[1].split(","))
					}
					val searchTime = System.currentTimeMillis() - startTime
					val returnText = searchResult.map { (score, name) -> "$score - $name" }.joinToString("\n")
					return exchange.sendStringAndClose(400,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						"Search time: $searchTime ms\n$returnText\n")
				}
			}
			"PUT" -> {
				if (path == "/categories") {
					File("resources/categories.txt").bufferedWriter().use { out -> out.write(reqBody) }
					return exchange.sendStringAndClose(204, mapOf(), "")
				}
			}
			"POST" -> {
				if (path == "/start-scraping") {
					if (SCRAPING_NOW) {
						return exchange.sendStringAndClose(409,
							mapOf("Content-Type" to "text/html; charset=UTF-8"),
							"Already scraping!\n")
					}
					SCRAPING_NOW = true
					exchange.sendStringAndClose(204, mapOf(), "")
					// The runBlocking() call shouldn't be problematic
					// because it's usually invoked with GlobalScope.launch
					// from CoroutineExecutor. Otherwise it is waited on
					// with invokeAll() or invokedAny() functions
					runBlocking {
						// Main scraping routine
						downloadSelectedCategories()
					}
					SCRAPING_NOW = false
				}
			}
		}

		exchange.sendStringAndClose(404, mapOf("Content-Type" to "text/plain; charset=UTF-8"), "Method $reqMethod not found for $path\n")
	}

	override fun handle(exchange: HttpExchange) {
		try {
			_handle(exchange)
		} catch (e: Exception) {
			val errorMsg = "Server error: $e"
			println(errorMsg)
			Logger.addLog(errorMsg)
			exchange.sendStringAndClose(500,
				mapOf("Content-Type" to "text/plain; charset=UTF-8"),
				errorMsg + "\n")
		}
	}
}

fun main(args: Array<String>) {
	val port = if (args.size == 1 && args[0].toIntOrNull() != null) {
		args[0].toInt()
	} else {
		6237
	}
	// Start the HTTP Server
	val httpServer = HttpServer.create(InetSocketAddress(port), 0) // 0 to reject backlogged connections
	httpServer.createContext("/", HomePageHandler())
	httpServer.setExecutor(
		// Set the amount of threads to use for handling web requests
		CoroutineExecutor()
	)
	httpServer.start()
	println("HTTP server running on port $port")
}
