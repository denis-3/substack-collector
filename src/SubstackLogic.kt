package substacklogic

import articlefetcher.customHttp2Request
import articlefetcher.Logger
import articlefetcher.HttpResponseSummary
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.lang.Math
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Attribute
import org.json.JSONObject
import org.json.JSONArray

// Directory where output files will be stored (like downloaded articles)
// No need for a trailing slash here
val BASE_OUTPUT_PATH = "./data"

private val DATE_RE = Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (0?[1-9]|[12][0-9]|3[01]), \\d{4}")
private val DATE_RE_2 = Regex("(0[1-9]|1[0-2])\\.(0[1-9]|[12][0-9]|3[01])\\.(\\d{2}|\\d{4})")
private val DATE_RE_3 = Regex("\\\"post_date\\\":\\\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)\\\"") // last resort
private val HELP_TEXT = """Command line options:
  article [articleUrl]                  Get a particular article by its link. Example: article https://read.technically.dev/p/whats-javascript
  authorSubdomain [author's subdomain]  Get articles from an author's subdomain. For example, "authorSubdomain marketsentiment" will get articles from marketsentiment.substack.com
  category [category ID]                Get articles from the top 100 rising authors in a category based on its ID. To get articles from the technology category (which has an ID of 4), use "category 4"
  all                                   Get all categories as defined in a categories.txt file, and authors in subdomain-list.txt

To make this work with Gradle, use its --args switch: gradle run --args "category 62" will invoke this class to get articles from category ID 62 (Business)"""

// Unsupported tags in the outer processing loop
class UnsupportedElementException(val elementTagName: String) : Exception("Unsupported top-level tag name: $elementTagName")
// Unsupported tags in parseInnerHtmlToMd()
class UnsupportedInnerElementException(val elementTagName: String) : Exception("Unsupported child tag name: $elementTagName")
// Unsupported div class name in outer loop
class UnsupportedDivClassName(val divClassName: String) : Exception("Unsupported div class name: $divClassName")
// Unsupported div class name in parseInnerHtmlToMd()
class UnsupportedInnerDivClassName(val divClassName: String) : Exception("Unsupported child div class name: $divClassName")

// Convenience extension function
private fun File.writeStr(str: String) = this.bufferedWriter().use { out -> out.write(str) }

// Truncates a string to a given length and adds ...
private fun String.truncateToLength(targetLength: Int): String =
	if (this.length > targetLength) this.slice(0..<targetLength).trim() + "..."
	else this

// Used as the return result for getTopArticlesByKeyword()
data class ArticleSearchResult(val score: Double, val title: String, val author: String, val fileName: String)

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

// Parse a div with a class name
// divs can take on different class names for different purposes
private fun parseDivVariety(elm: Element): String? {
	if (elm.tagName() != "div") throw IllegalArgumentException("Element is not a div")
	when (elm.className()) {
		"captioned-image-container" -> {
			val imgElm = elm.selectFirst("img")
			if (imgElm == null) throw Exception("No img element in a div.captioned-image-container")
			val imgElmSrc = imgElm.attribute("src")
			if (imgElmSrc == null) throw Exception("img in div.captioned-image-container has no src attribute")
			val imgCaptionElm = elm.selectFirst("figcaption")
			if (imgCaptionElm == null) {
				return "[Image](${imgElmSrc.value})"
			}
			return "[Image](${imgElmSrc.value}): ${parseInnerHtmlToMd(imgCaptionElm).trim()}"
		}
		// No caption for this one
		"image3" -> {
			val img3Json = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			return "[Image](${img3Json.getString("src")})"
		}
		"image-gallery-embed" -> {
			val imageGalleryJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val imageGalleryImages = imageGalleryJson.getJSONObject("gallery").getJSONArray("images")
			var markdown = ""
			for (i in 0..<imageGalleryImages.length()) {
				markdown += "[Image ${i+1}](${imageGalleryImages.getJSONObject(i).getString("src")})\n"
			}
			markdown += imageGalleryJson.getJSONObject("gallery").getString("caption")
			return markdown
		}
		"captioned-button-wrap" -> {
			// A simple button with a "preamble"
			val captionedButtonJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val buttonPreambleDiv = elm.selectFirst("div.preamble")
			if (buttonPreambleDiv == null) throw Exception("div.preamble not found in div.captioned-button-wrap")
			return "> *${parseInnerHtmlToMd(buttonPreambleDiv).trim()}*\n> [${captionedButtonJson.getString("text")}](${
				captionedButtonJson.getString("url")})"
		}
		"pullquote" -> {
			return "> " + parseInnerHtmlToMd(elm).trim().split("\n").joinToString("\n>")
		}
		"footnote" -> {
			// First child is an <a> of the footnote number
			// Second child is the footnote content
			val children = elm.children()
			if (children.size != 2) throw Exception("Footnote definition does not have two children")
			if (children[0].tagName() != "a") throw Exception("Footnote first child is not an a")
			return "[^${children[0].text()}]: ${parseInnerHtmlToMd(children[1]).trim()}"
		}
		// Can't parse these because their outer HTML looks like:
		// <div class="native-video-embed" data-component-name="VideoPlaceholder" data-attrs="{&quot;mediaUploadId&quot;:&quot;f045684f-cc9d-41f9-b56c-dc10eb284358&quot;,&quot;duration&quot;:null}"></div>
		"native-video-embed" -> {
			return "[Video Embed]"
		}
		// Can't parse this either
		// Outer HTML: <div class="native-audio-embed" data-component-name="AudioPlaceholder" data-attrs="{&quot;label&quot;:null,&quot;mediaUploadId&quot;:&quot;c910213c-5c5b-4bcc-98c8-d51ad9807872&quot;,&quot;duration&quot;:881.24084,&quot;isEditorNode&quot;:true}"></div>
		"native-audio-embed" -> {
			return "[Audio Embed]"
		}
		"digest-post-embed" -> {
			// This has a data-attrs with JSON about the post
			val dataAttrs = elm.attribute("data-attrs")
			if (dataAttrs == null) throw Exception("data-attrs attribute not found in div.digest-post-embed")
			val postEmbedJson = JSONObject(dataAttrs.value.replace("&quot;", "\""))
			val postEmbedTitle = postEmbedJson.getString("title")
			val bylines = mutableListOf<String>()
			if (postEmbedJson.getBoolean("showBylines") == true) {
				val bylinesArr = postEmbedJson.getJSONArray("publishedBylines")
				for (i in 0..<bylinesArr.length()) {
					bylines.add(bylinesArr.getJSONObject(i).getString("name"))
				}
			}
			val bylinesString = when (bylines.size) {
				0 -> ""
				1 -> "By ${bylines[0]}\n\n"
				2 -> "By ${bylines[0]} and ${bylines[1]}\n\n"
				else -> "By" + bylines.slice(0..(bylines.size-2)).joinToString(", ") + ", and ${bylines[bylines.size - 1]}\n\n"
			}
			val postEmbedCaption = postEmbedJson.getString("caption")
			val postEmbedCoverImg = postEmbedJson.getString("cover_image")
			// The call-to-action (cta) is text like "Read more"
			val postEmbedCta = if (postEmbedJson.isNull("cta")) "Read full story" else postEmbedJson.getString("cta")
			val postEmbedUrl = postEmbedJson.getString("canonical_url")
			return "---\n\n### $postEmbedTitle\n\n$bylinesString[Cover Image]($postEmbedCoverImg)\n\n$postEmbedCaption\n\n" +
				"[$postEmbedCta ->]($postEmbedUrl)\n\n---"
		}
		// Very similar to div.digest-post-embed
		"embedded-post-wrap" -> {
			val dataAttrs = elm.attribute("data-attrs")
			if (dataAttrs == null) throw Exception("data-attrs attribute not found in div.digest-post-embed")
			val postEmbedJson = JSONObject(dataAttrs.value.replace("&quot;", "\""))
			val postEmbedTitle = postEmbedJson.getString("title")
			val postEmbedDesc = if (postEmbedJson.isNull("truncated_body_text")) ""
				else postEmbedJson.getString("truncated_body_text") + "\n\n"
			val postEmbedUrl = postEmbedJson.getString("url")
			val bylines = mutableListOf<String>()
			val bylinesArr = postEmbedJson.getJSONArray("bylines")
			for (i in 0..<bylinesArr.length()) {
				bylines.add(bylinesArr.getJSONObject(i).getString("name"))
			}
			val bylinesString = when (bylines.size) {
				0 -> ""
				1 -> "By ${bylines[0]}\n\n"
				2 -> "By ${bylines[0]} and ${bylines[1]}\n\n"
				else -> "By" + bylines.slice(0..(bylines.size-2)).joinToString(", ") + ", and ${bylines[bylines.size - 1]}\n\n"
			}
			return "---\n\n### $postEmbedTitle\n\n$bylinesString$postEmbedDesc" +
				"[Read More]($postEmbedUrl)\n\n---"
		}
		"meeting-embed" -> {
			val meetingJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val meetingPerson = meetingJson.getString("name")
			val meetingLink = meetingJson.getString("url")
			return "[Book a meeting with $meetingPerson]($meetingLink)"
		}
		// This one is used for some interactive graphs
		"datawrapper-wrap outer" -> {
			val dataWrapperJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val dataTitle = dataWrapperJson.getString("title")
			val dataDesc = dataWrapperJson.getString("description")
			val interactiveUrl = dataWrapperJson.getString("url") // iframe of the datawrapper
			val thumbnailUrl = dataWrapperJson.getString("thumbnail_url_full") // static thumbnail image
			return "[$dataTitle: $dataDesc]($interactiveUrl) ([Static version]($thumbnailUrl))"
		}
		"youtube-wrap" -> {
			// These divs have an ID in the form of youtube2-videoIdHere
			val ytVideoId = elm.id().split("-")[1]
			return "[YouTube Video Embed](https://youtu.be/${ytVideoId})"
		}
		// An embedded Substack comment
		"comment" -> {
			val commentJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
				.getJSONObject("comment")
			val poster = commentJson.getString("name")
			val commentBody = commentJson.getString("body").replace("\\n", "\n")
			return "Comment from $poster: $commentBody"
		}
		// Best thing to do is just get the iframe's URL
		"apple-podcast-container" -> {
			val iframeElm = elm.selectFirst("iframe")
			if (iframeElm == null) throw Exception("iframe not found in div.apple-podcast-container")
			val podcastJson = JSONObject(iframeElm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val podcastName = podcastJson.getString("podcastTitle")
			val podcastAuthor = podcastJson.getString("podcastByline")
			val podcastUrl = podcastJson.getString("targetUrl")
			return "[Listen to \"$podcastName\" by $podcastAuthor on Apple Podcasts]($podcastUrl)"
		}
		"file-embed-wrapper" -> {
			val fileName = elm.selectFirst("div.file-embed-details-h1")
			if (fileName == null) throw Exception("div.file-embed-details-h1 not found in div.file-embed-wrapper")
			val fileDetails = elm.selectFirst("div.file-embed-details-h2") // "fileDetails" is a string of the file size and type
			if (fileDetails == null) throw Exception("div.file-embed-details-h2 not found in div.file-embed-wrapper")
			val fileDownloadBtn = elm.selectFirst("a.file-embed-button")
			if (fileDownloadBtn == null) throw Exception("div.file-embed-wrapper has no a.file-embed-button")
			return "[Download ${fileName.text()} (${fileDetails.text()})](${fileDownloadBtn.attribute("href")!!.value})"
		}
		"tweet" -> {
			val tweetJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val tweetAuthor = tweetJson.getString("name")
			val tweetAuthorUsername = tweetJson.getString("username")
			val tweetText = tweetJson.getString("full_text").replace("\\n", "\n")
			return "[$tweetAuthor (@$tweetAuthorUsername) posted on X: \"$tweetText\"](${tweetJson.getString("url")})"
		}
		"instagram" -> {
			val postJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val postAuthor = postJson.getString("author_name")
			val postTitle = postJson.getString("title")
			val postId = postJson.getString("instagram_id")
			return "[@$postAuthor posted on Instagram: $postTitle](https://instagram.com/p/$postId)"
		}
		"bluesky-wrap outer" -> {
			val postJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val postAuthor = postJson.getString("authorName")
			val postAuthorUsername = postJson.getString("authorHandle")
			val postText = postJson.getString("text")
			// Have to get URL from iframe element
			val postEmbed = elm.selectFirst("iframe")
			if (postEmbed == null) throw Exception("iframe not found in div.bluesky-wrap.outer")
			return "[$postAuthor (@$postAuthorUsername) posted on BlueSky: \"$postText\"](${postEmbed.attribute("src")!!.value})"
		}
		"soundcloud-wrap" -> {
			val scTrackJson = JSONObject(elm.attribute("data-attrs")!!.value.replace("&quot;", "\""))
			val trackTitle = scTrackJson.getString("title")
			val trackAuthor = scTrackJson.getString("author_name")
			val trackDescr = scTrackJson.getString("description").truncateToLength(60).replace("\\n", "\n")
			val trackUrl = scTrackJson.getString("targetUrl")
			return "[Listen to \"$trackTitle\" by $trackAuthor on SoundCloud]($trackUrl): $trackDescr"
		}
		// Some ignored div class names
		// TODO: bring back parsing for socials if possible
		"poll-embed", "embedded-publication-wrap", "paywall-jump",
		"subscription-widget-wrap-editor", "community-chat",
		"directMessage button", "install-substack-app-embed install-substack-app-embed-web" -> return null
		else -> throw UnsupportedDivClassName(elm.className())
	}
}

// Parse the inner HTML of an element to markdown
private fun parseInnerHtmlToMd(elm: Element, onlyInlineElements: Boolean = false): String {
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
				if (child.className() == "footnote-hovercard-target") {
					res[i] = "[^${child.text()}]"
				} else {
					res[i] = child.text()
				}
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
			"hr" -> {
				res[i] = "---"
			}
			"sup", "sub" -> {
				// Not all markdown applications will display this format
				// Since markdown does not natively support {sub|super}scripts
				val ctn = child.tagName()
				res[i] = "<$ctn>${child.text()}</$ctn>"
			}
			"div" -> {
				try {
					if (child.className() == "") {
						res[i] = parseInnerHtmlToMd(child)
					} else {
						val parsedDivMd = parseDivVariety(child)
						if (parsedDivMd != null) {
							res[i] = parsedDivMd
						}
					}
				} catch(e: UnsupportedDivClassName) {
					// Rethrow exception but with the "Inner" version
					throw UnsupportedInnerDivClassName(e.divClassName)
				}
			}
			else -> throw UnsupportedInnerElementException(child.tagName())
		}
	}
	return res.joinToString("")
}

suspend fun scrapeArticle(articleLink: String): String {
	Logger.addLog("Scraping $articleLink...")
	val webResult = customHttp2Request(articleLink)

	// Save article HTML for debugging (it's overwritten on subsequent calls of scrapeArticle())
	val tmpArticleFile = File("$BASE_OUTPUT_PATH/article.html")
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

	val articleApiData: JSONObject
	// Get the real HTML content of the article
	// Need a special API call for some posts
	if (articleLink.startsWith("https://substack.com/home/post/p-")) {
		// "https://substack.com/home/post/p-".length == 33
		val apiResult = customHttp2Request("https://substack.com/api/v1/posts/by-id/" + articleLink.slice(33..articleLink.length))
		articleApiData = JSONObject(apiResult.text)
	} else {
		val specialScript = articleDoc.select("script").find { it.html().startsWith("window._preloads") }?.html()
		if (specialScript == null) throw Exception("Could not find article API data embedded in page")
		articleApiData = JSONObject(specialScript.slice(38..(specialScript.length-2)).replace("\\\"", "\"").replace("\\\\","\\"))
	}
	val actualArticleText = articleApiData.getJSONObject("post").getString("body_html")
	if (actualArticleText == null) throw Exception("No post.body_html field in Substack article API data")
	tmpArticleFile.writeStr(articleApiData.toString())

	// Array of objects describing the authors
	val articlePublishedBy = articleApiData.getJSONObject("post").getJSONArray("publishedBylines")
	val authorList = mutableListOf<String>()
	for (i in 0..<articlePublishedBy.length()) {
		authorList.add(articlePublishedBy.getJSONObject(i).getString("name"))
	}
	val authorStr = when (authorList.size) {
		0 -> {
			// Publication info can be either in "pub" or "publication"
			if (articleApiData.has("pub")) {
				articleApiData.getJSONObject("pub").getString("name")
			} else {
				articleApiData.getJSONObject("publication").getString("name")
			}
		}
		1 -> {
			"Original author: ${authorList[0]}"
		}
		2 -> {
			"Original authors: ${authorList[0]} and ${authorList[1]}"
		}
		else -> {
			"Original authors: ${authorList.slice(0..<(authorList.size-1)).joinToString(", ")}, and ${authorList.last()}"
		}
	}

	val localDateTime = LocalDateTime.now()
	val zonedDateTime = ZonedDateTime.of(localDateTime, ZoneId.systemDefault())
	val nowDateString = zonedDateTime.format(DateTimeFormatter.ofPattern("eee, MMM dd, YYYY, HH:mm:ss z"))
	// This is the main string
	var parsedArticle = "Original URL: $articleLink\n$authorStr\nScrape time: ${nowDateString}\n\n# $articleTitle"
	if (articleSubtitle != null) {
		parsedArticle += "\n## $articleSubtitle"
	}
	parsedArticle += "\n### $articleDate"

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
			"div" -> {
				if (elm.className() == "") {
					parsedArticle += "\n\n" + parseInnerHtmlToMd(elm)
				} else {
					val parsedDivMd = parseDivVariety(elm)
					if (parsedDivMd != null) {
						parsedArticle += "\n\n" + parsedDivMd
					}
				}
			}
			"blockquote" -> {
				// Same as div.pullquote
				parsedArticle += "\n\n> " + parseInnerHtmlToMd(elm).trim().split("\n").joinToString("\n>")
			}
			"pre", "code" -> {
				parsedArticle += "\n\n```\n${elm.text()}\n```"
			}
			"iframe" -> {
				// This one is similar to div.apple-podcast-container
				if (elm.hasClass("spotify-wrap")) {
					val podcastJson = JSONObject(elm.attribute("data-attrs")!!.value)
					val podcastFullTitle = podcastJson.getString("title") + ":" +
						podcastJson.getString("subtitle")
					val podcastUrl = podcastJson.getString("url")
					parsedArticle += "\n\n[Listen to \"$podcastFullTitle\" on Spotify]($podcastUrl)"
				} else {
					// Error on other iframes (must deliberately ignore them)
					throw UnsupportedElementException(elm.tagName())
				}
			}
			else -> {
				throw UnsupportedElementException(elm.tagName())
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
		val apiResult: HttpResponseSummary;
		try {
			apiResult = customHttp2Request("https://$authorSubdomain.substack.com/api/v1/archive?sort=new&search=&offset=$offset&limit=20")
		} catch (e: Exception) {
			// Usually this happens when the server aggressively
			// responds with 429 or cannot establish connection
			Logger.addLog("Unable to fetch articles for $authorSubdomain: $e")
			break
		}
		val apiArticles = JSONArray(apiResult.text)
		val apiArticlesLength = apiArticles.length()
		if (apiArticlesLength == 0) break
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

// This returns rising subdomains and saves them to resources/subdomain-list.txt
suspend fun getRisingSubdomains(categoryId: Int): Set<String> {
	// Get authors first
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

	// Save them to a persistent text file
	val mutAuthors = authors.toMutableList()
	val subdomainFile = File("resources/subdomain-list.txt")
	subdomainFile.createNewFile() // does nothing if it already exists
	val subdomainReader = subdomainFile.bufferedReader()
	while (subdomainReader.ready() && !mutAuthors.isEmpty()) {
		val line = subdomainReader.readLine()
		val deleteAuthorIdxs = mutableListOf<Int>()
		for (i in mutAuthors.indices.reversed()) {
			if (line == mutAuthors[i]) {
				deleteAuthorIdxs.add(i)
			}
		}
		for (i in deleteAuthorIdxs) {
			mutAuthors.removeAt(i)
		}
	}

	subdomainReader.close()
	if (!mutAuthors.isEmpty()) {
		FileOutputStream(subdomainFile, true).bufferedWriter().use { it.write(authors.joinToString("\n") + "\n") }
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
private fun parseCategoriesTextSpec(inp: String): Set<Int> {
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

// Update rising authors from categories in categories.txt
// And download their articles
suspend fun updateAuthorsAndDownload(maxLimitPerAuthor: Int = 50) {
	val categories = parseCategoriesTextSpec(File("resources/categories.txt").bufferedReader().use { it.readText() })
	for (category in categories) {
		getRisingSubdomains(category)
	}

	val subdomainReader = File("resources/subdomain-list.txt").bufferedReader()
	while (subdomainReader.ready()) {
		val author = subdomainReader.readLine()
		downloadArticlesFromAuthor(author, maxLimitPerAuthor, true)
	}
}

// Get most relevant articles by a query of keywords
// It returns a pair of search results, total articles searched
suspend fun getTopArticlesByKeyword(keywords: List<String>, articleCount: Int = 5): Pair<List<ArticleSearchResult>, Int> {
	val lowerKeywords = keywords.map { it.lowercase() }
	val totalKeywordCt = keywords.size
	// Pair of score, article UID hash
	val topArticles = MutableList<ArticleSearchResult>(articleCount) { ArticleSearchResult(0.0, "", "", "") }
	var totalArticles = 0
	for (combo in 0..255) {
		val hex = combo.toHexString(HexFormat {number { minLength = 2; removeLeadingZeros = true } } )
		val files = File("$BASE_OUTPUT_PATH/${hex[0]}/${hex[1]}").listFiles()
		if (files != null) {
			for (file in files) {
				totalArticles ++
				val fullText = file.bufferedReader().use { it.readText() }
				// How many keywords are present in the article
				var keywordInclusionCt = 0
				var articleScore = 0.0
				for (kw in lowerKeywords) {
					var kwCount = 0
					var latestKwIndex = fullText.indexOf(kw)
					while (latestKwIndex != -1) {
						kwCount ++
						latestKwIndex = fullText.indexOf(kw, latestKwIndex + 1)
					}
					if (kwCount == 0) continue
					keywordInclusionCt ++
					// Article score is exponential
					// The handy left bit shift operator can do powers of two!
					articleScore += kwCount * (1 shl kw.length)
				}

				articleScore *= Math.pow(keywordInclusionCt.toDouble() /
					totalKeywordCt.toDouble(), 2.toDouble()) / Math.sqrt(fullText.length.toDouble())

				// If it has a score better than the worst top article, add it in the array and recompute
				if (articleScore > topArticles[articleCount - 1].score) {
					// Author is on the second line after "Original author: ..."
					val newlineIdx = fullText.indexOf("\n")
					val author = fullText.slice((fullText.indexOf(":", newlineIdx)+2)..<fullText.indexOf("\n", newlineIdx+1))
					// Title is always on the fifth line of the file, after the first # character
					val hashtagIdx = fullText.indexOf("#")
					val title = fullText.slice((hashtagIdx+2)..<(fullText.indexOf("\n", hashtagIdx)))
					topArticles.add(ArticleSearchResult(articleScore, title, author, file.getName()))
					topArticles.sortBy { -it.score } // The sort is descending by default
					topArticles.removeLast()
				}
			}
		}
	}
	return Pair(topArticles, totalArticles)
}

suspend fun main(args: Array<String>) = coroutineScope {
	Logger.preserveLogsInMemory = false
	if (args.size == 1 && args[0] == "all") {
		launch { updateAuthorsAndDownload(50) }.join()
	} else if (args.size == 0 || args.size > 2) {
		println(HELP_TEXT)
	} else {
		when (args[0]) {
			"article" -> {
				println(async { scrapeArticle(args[1]) }.await())
			}
			"authorSubdomain" -> {
				launch { downloadArticlesFromAuthor(args[1], 50) }.join()
			}
			"category" -> {
				launch { downloadArticlesByCategory(args[1].toInt()) }.join()
			}
			else -> {
				println(HELP_TEXT)
			}
		}
	}
}
