import articlefetcher.Logger
import substacklogic.downloadSelectedCategories
import substacklogic.getTopArticlesByKeyword
import substacklogic.ArticleSearchResult
import substacklogic.BASE_OUTPUT_PATH
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.DelicateCoroutinesApi

// Map of paths to files and content-type (only supports GET)
private val FILE_PATHS_MAP = mapOf<String, List<String>>(
	"/" to listOf("resources/web/index.html", "text/html; charset=UTF-8"),
	"/config" to listOf("resources/web/config.html", "text/html; charset=UTF-8"),
	"/categories" to listOf("resources/categories.txt", "text/plain; charset=UTF-8"),
	"/assets/settings.svg" to listOf("resources/web/assets/settings.svg", "image/svg+xml"),
	"/assets/search.svg" to listOf("resources/web/assets/search.svg", "image/svg+xml")
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

fun parseQueryParams(fullURI: String): Map<String, String> {
	val questionIdx = fullURI.indexOf("?")
	if (questionIdx == -1) return mapOf()

	// Generic "previous index" for keeping track of interesting character occurences
	val qpMap = mutableMapOf<String, String>()
	val queryStr = fullURI.slice((questionIdx+1)..<fullURI.length)
	var prevIdx = 0
	var qpKey = "" // query param key
	for (i in queryStr.indices) {
		val char = queryStr[i]
		if (char == '=') {
			qpKey = queryStr.slice(prevIdx..<i)
			prevIdx = i + 1
		} else if (char == '&') {
			qpMap[qpKey] = queryStr.slice(prevIdx..<i)
			prevIdx = i + 1
		}
	}

	// End of string check
	if (!qpKey.isEmpty()) {
		qpMap[qpKey] = queryStr.slice(prevIdx..<queryStr.length)
	}

	return qpMap
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
		val outBytes = content.toByteArray()
		this.sendResponseHeaders(statusCode, outBytes.size.toLong())
		val outStream = this.responseBody
		outStream.write(outBytes)
		outStream.close()
	}
}

class HomePageHandler() : HttpHandler {
	suspend fun _handle(exchange: HttpExchange) {
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
		var reqBodySize: Int = 0
		while (reqBodyStream.read(buffer).also{ reqBodySize += it } != -1) {
			if (reqBodySize >= MAX_HTTP_BODY_SIZE) {
				return exchange.sendStringAndClose(413,
					mapOf("Content-Type" to "text/plain; charset=UTF-8"),
					"Request body larger than 10 KiB")
			}
			reqBody += buffer.toString(Charsets.UTF_8)
		}

		reqBody = reqBody.slice(0..reqBodySize)

		val reqMethod = exchange.requestMethod
		val queryParams = parseQueryParams(fullURI)
		when (reqMethod) {
			"GET" -> {
				if (path in FILE_PATHS_MAP) {
					val (fileName, contentType) = FILE_PATHS_MAP[path]!!
					val targetFileText = File(fileName).bufferedReader().use { it.readText() }
					return exchange.sendStringAndClose(200,
						mapOf("Content-Type" to contentType),
						targetFileText)
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
					if ("kws" !in queryParams || queryParams["kws"] == "") {
						return exchange.sendStringAndClose(400,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						"'kws' must be a query parameter with a comma-separated list of keywords\n")
					}
					val startTime = System.currentTimeMillis()
					val (searchResult, articleCount) = getTopArticlesByKeyword(queryParams["kws"]!!.split(","))
					val searchTime = System.currentTimeMillis() - startTime
					val returnText = searchResult.map {
						// Article details are split by null character
						(score, title, author, fn) -> "$score\u0000$title\u0000$author\u0000$fn"
					}.joinToString("\n")
					return exchange.sendStringAndClose(200,
						mapOf("Content-Type" to "text/plain; charset=UTF-8"),
						"Searched $articleCount articles in $searchTime ms\n$returnText\n")
				} else if (path.startsWith("/download/")) {
					val fileNamePathParam = path.slice((path.indexOf("/", 1)+1)..<path.length)
					// (sha256("any string") + ".md").length == 67
					if (fileNamePathParam.length != 67) {
						return exchange.sendStringAndClose(400,
							mapOf("Content-Type" to "text/plain; charset=UTF-8"),
							"Must have a path parameter corresponding to a downloaded article file name\n")
					}
					val articleFile = File("$BASE_OUTPUT_PATH/${fileNamePathParam[0]}/${fileNamePathParam[1]}/$fileNamePathParam")
					if (!articleFile.isFile()) {
						return exchange.sendStringAndClose(404,
							mapOf("Content-Type" to "text/plain; charset=UTF-8"),
							"This article file was not found in the storage\n")
					}
					val articleText = articleFile.bufferedReader().use { it.readText() }
					return exchange.sendStringAndClose(200,
						mapOf("Content-Type" to "text/markdown; charset=UTF-8",
							"Content-Disposition" to "attachment; filename=$fileNamePathParam"),
						articleText)
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
					// Main scraping routine
					downloadSelectedCategories()
					SCRAPING_NOW = false
				}
			}
		}

		exchange.sendStringAndClose(404, mapOf("Content-Type" to "text/plain; charset=UTF-8"), "Method $reqMethod not found for $path\n")
	}

	// A call to GlobalScope is fine here as long as the operations
	// in _handle are "well-behaved"
	@OptIn(DelicateCoroutinesApi::class)
	override fun handle(exchange: HttpExchange) {
		GlobalScope.launch {
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
	// The default executor is fine since all the processing happens in coroutines
	// httpServer.setExecutor(null)
	httpServer.start()
	println("HTTP server running on port $port")
}
