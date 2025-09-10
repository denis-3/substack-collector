import articlefetcher.customHttp2Request
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import org.jsoup.Jsoup

private val DATE_RE = Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (0?[1-9]|[12][0-9]|3[01]), \\d{4}")
private val DATE_RE_2 = Regex("(0[1-9]|1[0-2])\\.(0[1-9]|[12][0-9]|3[01])\\.(\\d{2}|\\d{4})")

suspend fun scrapeArticle(articleLink: String): String {
	println("Scraping $articleLink...")
	val webResult = coroutineScope { async { customHttp2Request(articleLink) }.await() }

	// Save article HTML for debugging (is overwritten on subsequent calls of scrapeArticle())
	File("tmp/article.html").bufferedWriter().use { out -> out.write(webResult.text) }
	val articleDoc = Jsoup.parse(webResult.text)

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

	if (articleDate == null) {
		throw Exception("Article date is null!")
	}

	return "Title: $articleTitle\nSubtitle: $articleSubtitle\nDate: $articleDate"
}

suspend fun main() {
	println("Starting main()...")
	// Scrape random article from Substack, to showcase and test scrapeArticle()
	println("Running scrapeArticle()...")
	val returned = scrapeArticle("https://read.technically.dev/p/technically-monthly-september-2025")
	println("Returned value from scrapeArticle():\n$returned")
}
