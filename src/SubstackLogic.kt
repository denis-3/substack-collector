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
	val children = elm.children()
	if (children.size == 0) return elm.text()
	var res = ""
	for (child in children) {
		when (child.tagName()) {
			"p" -> {
				res += parseInnerHtmlToMd(child) + "\n"
			}
			"span" -> {
				res += child.text()
			}
			"strong", "h1", "h2", "h3", "h4", "h5", "h6" -> {
				val parsedBoldText = parseInnerHtmlToMd(child)
				res += if (parsedBoldText.endsWith(" ")) "**${parsedBoldText.slice(0..(parsedBoldText.length-1))}** " else
					"**$parsedBoldText**"
			}
			"em" -> {
				val parsedItalicText = parseInnerHtmlToMd(child)
				res += if (parsedItalicText.endsWith(" ")) "*${parsedItalicText.slice(0..(parsedItalicText.length-1))}* " else
					"*$parsedItalicText*"
			}
			"s" -> {
				val parsedStrikeText = parseInnerHtmlToMd(child)
				res += if (parsedStrikeText.endsWith(" ")) "*${parsedStrikeText.slice(0..(parsedStrikeText.length-1))}* " else
					"*$parsedStrikeText*"
			}
			"code", "pre" -> {
				val codeText = child.text()
				res += if (codeText.endsWith(" ")) "`$codeText` " else "`$codeText`"
			}
			"ul" -> {
				val points = child.children()
				for (point in points) {
					res += "  *" + parseInnerHtmlToMd(point).trim() + "\n"
				}
				res = res.slice(0..(res.length-1)) // remove trailing \n
			}
			"ol" -> {
				val points = child.children()
				for (i in points.indices) {
					res += "  ${i+1}." + parseInnerHtmlToMd(points[i]).trim() + "\n"
				}
				res = res.slice(0..(res.length-1)) // remove trailing \n
			}
			"blockquote" -> {
				res += ">" + parseInnerHtmlToMd(child)
			}
			else -> {
				res += "\nUnsupported child element tag name: ${child.tagName()}"
			}
		}
	}
	return res
}

suspend fun scrapeArticle(articleLink: String): String {
	println("Scraping $articleLink...")
	val webResult = coroutineScope { async { customHttp2Request(articleLink) }.await() }

	// Save article HTML for debugging (it's overwritten on subsequent calls of scrapeArticle())
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

	val localDateTime = LocalDateTime.now()
	val zonedDateTime = ZonedDateTime.of(localDateTime, ZoneId.systemDefault())
	val nowDateString = zonedDateTime.format(DateTimeFormatter.ofPattern("eee, MMM dd, YYYY, HH:mm:ss z"))
	// This is the main string
	var parsedArticle = "Original URL: $articleLink\nScrape time: ${nowDateString}\n\n# $articleTitle"
	if (articleSubtitle != null) {
		parsedArticle += "\n## $articleSubtitle"
	}
	parsedArticle += "\n### $articleDate"

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

	// Save real article HTML for debugging (it's overwritten on subsequent calls of scrapeArticle())
	File("tmp/article.html").bufferedWriter().use { out -> out.write(actualArticleText) }

	articleDoc = Jsoup.parse("<body>" + actualArticleText + "</body>")

	// Top-level and child elements have different semantics so that's why
	// there is processing in this loop and also parseInnerHtmlToMd()
	for (elm in articleDoc.select("body > *")) {
		val tn = elm.tagName()
		when (tn) {
			"p" -> {
				parsedArticle += "\n\n" + parseInnerHtmlToMd(elm)
			}
			"ul" -> {
				parsedArticle += "\n"
				val children = elm.children()
				for (point in children) {
					parsedArticle += "\n* " + parseInnerHtmlToMd(point)
				}
				parsedArticle = parsedArticle.slice(0..(parsedArticle.length-1))
			}
			"ol" -> {
				parsedArticle += "\n"
				val children = elm.children()
				for (i in children.indices) {
					parsedArticle += "\n${i+1}. " + parseInnerHtmlToMd(children[i])
				}
				parsedArticle = parsedArticle.slice(0..(parsedArticle.length-1))
			}
			"h1", "h2", "h3", "h4", "h5", "h6" -> {
				parsedArticle += "\n\n" + "#".repeat(tn[1].digitToInt()) + " " + elm.text()
			}
			"div", "blockquote" -> {
				if (tn == "div" && !elm.hasClass("pullquote")) continue
				parsedArticle += "\n\n>" + parseInnerHtmlToMd(elm)
			}
			else -> {
				parsedArticle += "Unsupported top-level element: ${elm.tagName()}"
			}
		}
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
