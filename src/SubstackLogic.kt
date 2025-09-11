import articlefetcher.customHttp2Request
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

private val DATE_RE = Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (0?[1-9]|[12][0-9]|3[01]), \\d{4}")
private val DATE_RE_2 = Regex("(0[1-9]|1[0-2])\\.(0[1-9]|[12][0-9]|3[01])\\.(\\d{2}|\\d{4})")
private val DATE_RE_3 = Regex("\\\"post_date\\\":\\\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)\\\"") // last resort

// Parse the inner HTML of an element to markdown
fun parseInnerHtmlToMd(elm: Element): String {
	when (elm.tagName()) {
		"p" -> {
			return "\n\n" + elm.text()
		}
		"ul" -> {
			var md = "\n"
			for (point in elm.children()) {
				md += "\n* " + point.text()
			}
			return md
		}
		"ol" -> {
			var md = "\n"
			val points = elm.children()
			for (i in points.indices) {
				md += "\n${i+1}. " + points[i].text()
			}
			return md
		}
		else -> {
			return "\nUnsupported element tag name: ${elm.tagName()}"
		}
	}
}

suspend fun scrapeArticle(articleLink: String): String {
	println("Scraping $articleLink...")
	val webResult = coroutineScope { async { customHttp2Request(articleLink) }.await() }

	// Save article HTML for debugging (is overwritten on subsequent calls of scrapeArticle())
	File("tmp/article.html").bufferedWriter().use { out -> out.write(webResult.text) }
	var articleDoc = Jsoup.parse(webResult.text)

	// Get header, author, and date first
	// Each one has multiple options to account for article formatting variety
	val articleTitle = articleDoc.selectFirst("h1.post-title")?.text() ?:
		articleDoc.selectFirst("h2.pencraft")?.text() ?:
		articleDoc.selectFirst("meta[property=\"og:title\"]")?.attribute("content")

	if (articleTitle == null) {
		throw Exception("Article title is null!")
	}

	// Subtitle is either h3.subtitle or a div adjacent to h2.pencraft
	// It may be null (some posts don't set a subtitle)
	val articleSubtitle = articleDoc.selectFirst("h3.subtitle")?.text() ?:
		articleDoc.selectFirst("h2.pencraft")?.nextElementSibling()?.text() ?:
		articleDoc.selectFirst("meta[property=\"description\"]")?.attribute("content")

	// Find article date by parsing a variety of Regexs
	var articleDate: String? = null
	for (elm in articleDoc.select("div.pencraft")) {
		val elmText = elm.text().trim()
		if (elmText.matches(DATE_RE) || elmText.matches(DATE_RE_2)) {
			articleDate = elmText
			break
		}
	}

	// last resort
	if (articleDate == null) articleDate = DATE_RE_3.find(webResult.text)?.value

	if (articleDate == null) {
		throw Exception("Article date is null!")
	}

	// This is the main string
	val localDateTime = LocalDateTime.now()
	val zonedDateTime = ZonedDateTime.of(localDateTime, ZoneId.systemDefault())
	val nowDateString = zonedDateTime.format(DateTimeFormatter.ofPattern("eee, MMM dd, YYYY, HH:mm:ss z"))
	var parsedArticle = "Original URL: $articleLink\nScrape time: ${nowDateString}"

	var actualArticleText: String = ""
	// Get the real HTML content of the article
	// Need a special API for some posts
	if (articleLink.startsWith("https://substack.com/home/post/p-")) {
		val apiResult = coroutineScope { async {
			// "https://substack.com/home/post/p-".length == 33
			customHttp2Request("https://substack.com/api/v1/posts/by-id/" + articleLink.slice(33..articleLink.length))
		}.await() }
		val actualArticleTextNull = JSONObject(apiResult.text).getJSONObject("post").getString("body_html")
		if (actualArticleTextNull == null) throw Exception("No post.body_html field in Substack post API response!")
		actualArticleText = actualArticleTextNull
	} else {
		for (script in articleDoc.select("script")) {
			// Look for the special script that has the post HTML body
			val tc = script.html() // text content
			if (tc.startsWith("window._preloads") == true) {
				val actualArticleTextNull = JSONObject(tc.slice(38..(tc.length-2)).replace("\\\"", "\"").replace("\\\\","\\"))
					.getJSONObject("post").getString("body_html")
				if (actualArticleTextNull == null) throw Exception("No post.body_html field in embedded Substack JSON data!")
				actualArticleText = actualArticleTextNull
				break
			}
		}
	}

	articleDoc = Jsoup.parse("<body>" + actualArticleText + "</body>")

	for (elm in articleDoc.select("body > *")) {
		parsedArticle += parseInnerHtmlToMd(elm)
	}

	return parsedArticle
}

suspend fun main() {
	println("Starting main()...")
	// Scrape random article from Substack, to showcase and test scrapeArticle()
	println("Running scrapeArticle()...")
	val returned = scrapeArticle("https://read.technically.dev/p/technically-monthly-september-2025")
	println("Returned value from scrapeArticle():\n$returned")
}
