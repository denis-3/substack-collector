package substacklogic

import articlefetcher.customHttp2Request
import articlefetcher.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

private val BASE_OUTPUT_PATH = "./tmp"

private val DATE_RE = Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (0?[1-9]|[12][0-9]|3[01]), \\d{4}")
private val DATE_RE_2 = Regex("(0[1-9]|1[0-2])\\.(0[1-9]|[12][0-9]|3[01])\\.(\\d{2}|\\d{4})")
private val DATE_RE_3 = Regex("\\\"post_date\\\":\\\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)\\\"") // last resort
private val HELP_TEXT = """Command line options:
  article [articleUrl]                  Get a particular article by its link. Example: article https://read.technically.dev/p/whats-javascript
  authorSubdomain [author's subdomain]  Get articles from an author's subdomain. For example, "authorSubdomain marketsentiment" will get articles from marketsentiment.substack.com
  category [category ID]                Get articles from the top 100 rising authors in a category based on its ID. To get articles from the technology category (which has an ID of 4), use "category 4"
  all                                   Get all categories as defined in a categories.txt file

To make this work with Gradle, use its --args switch: gradle run --args "category 62" will invoke this class to get articles from category ID 62 (Business)"""

// Convenience extension function
private fun File.writeStr(str: String) = this.bufferedWriter().use { out -> out.write(str) }

// "Article ID" is the numeric or text identifier used by Substack
// Split the URL based on these possible strings: /p/ /p-  /cp/  /cp-
private fun articleIdFromUrl(articleUrl: String) =
	when {
		articleUrl.contains("/p/") -> articleUrl.split("/p/")[1]
		articleUrl.contains("/p-") -> articleUrl.split("/p-")[1]
		articleUrl.contains("/cp/") -> articleUrl.split("/cp/")[1]
		articleUrl.contains("/cp-") -> articleUrl.split("/cp-")[1]
		else -> throw Exception("Can't get ID from article URL: $articleUrl")
	}

// Convenience hashing function
private fun sha256(inp: String): String {
	val msgDig = MessageDigest.getInstance("SHA-256")
	msgDig.update(inp.toByteArray())
	return msgDig.digest().joinToString("") {"%02x".format(it)}
}

// Parse the inner HTML of an element to markdown
private fun parseInnerHtmlToMd(elm: Element): String {
	// .children() returns HTML element children only
	val children = elm.children()
	if (children.size == 0) return elm.text()
	val res = MutableList<String>(elm.childNodeSize()) {""}
	// Parse only text nodes here
	for (node in elm.childNodes()) {
		if (node.normalName() == "#text") {
			res[node.siblingIndex()] = node.nodeValue()
		}
	}
	// Parse the proper element children
	for (child in children) {
		val i = child.siblingIndex()
		when (child.tagName()) {
			"p" -> {
				res[i] = parseInnerHtmlToMd(child) + "\n"
			}
			"br" -> {
				res[i] = "\n"
			}
			"span" -> {
				res[i] = child.text()
			}
			"strong", "h1", "h2", "h3", "h4", "h5", "h6" -> {
				val parsedBoldText = parseInnerHtmlToMd(child)
				res[i] += if (parsedBoldText.endsWith(" ")) "**${parsedBoldText.slice(0..(parsedBoldText.length-1))}** " else
					"**$parsedBoldText**"
			}
			"em" -> {
				val parsedItalicText = parseInnerHtmlToMd(child)
				res[i] += if (parsedItalicText.endsWith(" ")) "*${parsedItalicText.slice(0..(parsedItalicText.length-1))}* " else
					"*$parsedItalicText*"
			}
			"s" -> {
				val parsedStrikeText = parseInnerHtmlToMd(child)
				res[i] += if (parsedStrikeText.endsWith(" ")) "*${parsedStrikeText.slice(0..(parsedStrikeText.length-1))}* " else
					"*$parsedStrikeText*"
			}
			"code", "pre" -> {
				val codeText = child.text()
				res[i] += if (codeText.endsWith(" ")) "`$codeText` " else "`$codeText`"
			}
			"ul" -> {
				val points = child.children()
				for (point in points) {
					res[i] += "\n *" + parseInnerHtmlToMd(point).trim() + "\n"
				}
				res[i] = res[i].slice(0..(res[i].length-1)) // remove trailing \n
			}
			"ol" -> {
				val points = child.children()
				for (ii in points.indices) {
					res[i] += " ${ii+1}." + parseInnerHtmlToMd(points[ii]).trim() + "\n"
				}
				res[i] = res[i].slice(0..(res[i].length-1)) // remove trailing \n
			}
			"blockquote" -> {
				res[i] += ">" + parseInnerHtmlToMd(child)
			}
			"a" -> {
				res[i] = "[${parseInnerHtmlToMd(child)}](${child.attr("href")})"
			}
			else -> {
				res[i] += "\nUnsupported child element tag name: ${child.tagName()}"
			}
		}
	}
	return res.joinToString("")
}

suspend fun scrapeArticle(articleLink: String): String {
	Logger.addLog("Scraping $articleLink...")
	val webResult = customHttp2Request(articleLink)

	// Save article HTML for debugging (it's overwritten on subsequent calls of scrapeArticle())
	val tmpArticleFile = File("tmp/article.html")
	tmpArticleFile.writeStr(webResult.text)
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
		// "https://substack.com/home/post/p-".length == 33
		val apiResult = customHttp2Request("https://substack.com/api/v1/posts/by-id/" + articleLink.slice(33..articleLink.length))
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
	tmpArticleFile.writeStr(actualArticleText)

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
					parsedArticle += "\n* " + parseInnerHtmlToMd(point).trim()
				}
			}
			"ol" -> {
				parsedArticle += "\n"
				val children = elm.children()
				for (i in children.indices) {
					parsedArticle += "\n${i+1}. " + parseInnerHtmlToMd(children[i]).trim()
				}
			}
			"h1", "h2", "h3", "h4", "h5", "h6" -> {
				parsedArticle += "\n\n" + "#".repeat(tn[1].digitToInt()) + " " + elm.text()
			}
			"div", "blockquote" -> {
				if (tn == "div" && !elm.hasClass("pullquote")) continue
				parsedArticle += "\n\n> " + parseInnerHtmlToMd(elm).trim().split("\n").joinToString("\n>")
			}
			else -> {
				parsedArticle += "Unsupported top-level element: ${elm.tagName()}"
			}
		}
	}
	return parsedArticle
}

private suspend fun saveArticleToDisk(articleUid: String, articleMarkdown: String) {
	val hash = sha256(articleUid)
	if (!File(BASE_OUTPUT_PATH).isDirectory()) throw Exception("BASE_OUTPUT_PATH is not a directory: $BASE_OUTPUT_PATH")
	val thisArticleOutputPath = File("${BASE_OUTPUT_PATH}/${hash[0]}/${hash[1]}/")
	if (!thisArticleOutputPath.isDirectory()) thisArticleOutputPath.mkdirs()
	File("${BASE_OUTPUT_PATH}/${hash[0]}/${hash[1]}/$hash.md").writeStr(articleMarkdown)
}

suspend fun getArticlesFromAuthor(authorSubdomain: String, maxLimit: Int): Set<String> {
	Logger.addLog("Getting articles from $authorSubdomain...")
	val articles = mutableSetOf<String>()
	var offset = 0
	while (articles.size < maxLimit && offset < 300) { // hard-stop at offset 300
		delay(750) // Avoid calling the API too quickly
		val apiResult = customHttp2Request("https://$authorSubdomain.substack.com/api/v1/archive?sort=new&search=&offset=$offset&limit=20")
		if (apiResult.text.length < 10) break // For a short response, assume we're done
		val apiArticles = JSONArray(apiResult.text)
		val apiArticlesLength = apiArticles.length()
		for (i in 0..<apiArticlesLength) {
			val thisArticleObj = apiArticles.getJSONObject(i)
			if (thisArticleObj.getString("audience") == "everyone" && thisArticleObj.getString("type") != "podcast") {
				articles.add(thisArticleObj.getString("canonical_url"))
			}
		}
		offset += 20
	}
	return articles
}

suspend fun downloadArticlesFromAuthor(authorSubdomain: String, maxLimit: Int, skipExisting: Boolean = false) {
	val articles = getArticlesFromAuthor(authorSubdomain, maxLimit)
	for (articleUrl in articles) {
		val articleUid = authorSubdomain + "/" + articleIdFromUrl(articleUrl)
		if (skipExisting) {
			val artHash = sha256(articleUid)
			if (File("$BASE_OUTPUT_PATH/${artHash[0]}/${artHash[1]}/${artHash}.md").isFile()) {
				Logger.addLog("Skipping article $articleUid because it already exists...")
				continue
			}
		}
		val articleMd = scrapeArticle(articleUrl)
		saveArticleToDisk(articleUid, articleMd)
	}
}

suspend fun getRisingSubdomains(categoryId: Int): Set<String> {
	Logger.addLog("Getting rising authors for category $categoryId...")
	val authors = mutableSetOf<String>()
	var page = 0;
	var more = true
	while (more) {
		val apiResult = customHttp2Request("https://substack.com/api/v1/category/leaderboard/$categoryId/trending?page=$page")
		val apiJson = JSONObject(apiResult.text)
		val apiAuthors = apiJson.getJSONArray("items")
		val apiAuthorsLength = apiAuthors.length()
		for (i in 0..<apiAuthorsLength) {
			val pub = apiAuthors.getJSONObject(i).getJSONObject("publication")
			if (pub.getString("language") == "en") {
				authors.add(pub.getString("subdomain"))
			}
		}
		page ++
		more = apiJson.getBoolean("more")
	}
	return authors
}

suspend fun downloadArticlesByCategory(categoryId: Int, maxLimitPerAuthor: Int = 50) {
	val authors = getRisingSubdomains(categoryId)
	for (author in authors) {
		downloadArticlesFromAuthor(author, maxLimitPerAuthor, true)
	}
}

// Parses a simple text format where each line has a number and then optional text after a space
// Returns set of categories (bad lines are ignored)
fun parseCategoriesTextSpec(inp: String): Set<Int> {
	val categories = mutableSetOf<Int>()
	for (line in inp.split("\n")) {
		if (line.toIntOrNull() != null) categories.add(line.toInt())
		val spaceIndex = line.indexOf(" ")
		val sliced = line.slice(0..<spaceIndex)
		if (sliced.toIntOrNull() != null) {
			categories.add(sliced.toInt())
		}
	}
	return categories
}

suspend fun downloadSelectedCategories(maxLimitPerAuthor: Int = 50) {
	val categories = parseCategoriesTextSpec(File("categories.txt").bufferedReader().use { it.readText() })
	for (category in categories) {
		downloadArticlesByCategory(category, maxLimitPerAuthor)
	}
}

suspend fun main(args: Array<String>) = coroutineScope {
	Logger.preserveLogsInMemory = false
	if (args.size == 1 && args[0] == "all") {
		launch { downloadSelectedCategories(50) }.join()
	} else if (args.size == 0 || args.size > 2) {
		println(HELP_TEXT)
	} else {
		when (args[0]) {
			"article" -> {
				println(async { scrapeArticle(args[1]) }.await())
			}
			else -> {
				println(HELP_TEXT)
			}
		}
	}
}
