const fs = require("fs").promises
const crypto = require("crypto")
const cheerio = require("cheerio")
const customFetch = require("./customFetch.js")

const DATA_DIRECTORY_PARENT = "./mnt/" // MUST END WITH A SLASH HERE
const DATE_RE = /(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (0?[1-9]|[12][0-9]|3[01]), \d{4}/
const DATE_RE_2 = /(0[1-9]|1[0-2])\.(0[1-9]|[12][0-9]|3[01])\.(\d{2}|\d{4})/
const DATE_RE_3 = /\\"post_date\\":\\"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\\"/ // last resort
const ARTICLE_NAME_RE = /\/c?p[\/|-]/

function getUserAgent() {
	return USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)]
}

async function millis(m) {
	return new Promise(function (resolve, reject) {
		setTimeout(function () {
			resolve()
		}, m)
	})
}

function sha256(a) {
	return crypto.createHash("sha256").update(a).digest("hex")
}

// parse inner elements like <strong> into markdown
function parseInnerHtmlToMd(doc, e) {
	if (doc === undefined || e === undefined) throw Error("Undefined parameter")
	if (e.attr("class") == "captioned-image-container") return ""
	if (e.children() == 0) return e.prop("textContent")
	const children = e.contents() // this includes raw text nodes, too
	let res = ""
	for (var i = 0; i < children.length; i++) {
		const parsedChild = doc(children[i])
		const tn = parsedChild.prop("tagName")
		if (tn == "SPAN" || children[i].type == "text") {
			res += parsedChild.prop("textContent")
		} else if (tn == "BR") {
			res += "\n"
		} else if (tn == "STRONG" || /^H\d$/.test(tn)) { // treat headers like bold text
			const text = parseInnerHtmlToMd(doc, parsedChild)
			res += text.endsWith(" ") ? `**${text.slice(0, -1)}** ` : `**${text}**`
		} else if (tn == "EM") {
			const text = parseInnerHtmlToMd(doc, parsedChild)
			res += text.endsWith(" ") ? `*${text.slice(0, -1)}* ` : `*${text}*`
		} else if (tn == "S") {
			const text = parseInnerHtmlToMd(doc, parsedChild)
			res += text.endsWith(" ") ? `~~${text.slice(0, -1)}~~ ` : `~~${text}~~`
		} else if (tn == "CODE" || tn == "PRE") {
			const text = parseInnerHtmlToMd(doc, parsedChild)
			res += text.endsWith(" ") ? `\`${text.slice(0, -1)}\` ` : `\`${text}\``
		} else if (tn == "A") {
			res += `[${parsedChild.prop("textContent")}](${parsedChild.attr("href")})`
		} else if (tn == "SUP" || tn == "SUB") {
			res += `(${parsedChild.prop("textContent")})`
		} else if (tn == "P") {
			res += parseInnerHtmlToMd(doc, parsedChild) + "\n"
		} else if (tn == "BLOCKQUOTE") {
			res += ">" + parseInnerHtmlToMd(doc, parsedChild).trim().replaceAll("\n", "\n>")
		} else if (tn == "UL" || tn == "OL") {
			const bulletPoints = parsedChild.children()
			for (var ii = 0; ii < bulletPoints.length; ii++) {
				res += `  ${tn == "UL" ? "*" : (ii+1)+"."} `
				res += parseInnerHtmlToMd(doc, doc(bulletPoints[ii])).trim()
				res += (ii < bulletPoints.length - 1) ? "\n" : ""
			}
		} else if (tn == "LI") {
			res += parseInnerHtmlToMd(doc, parsedChild)
		} else if (tn == "HR") {
			res += "---"
		} else if (!["DIV", "IFRAME"].includes(tn)) { // some ignored inner tags
			console.error(e.prop("textContent"))
			throw Error("Unknown inner tag name " + tn)
		}
	}
	// no parsable text children. return top level text content
	if (res == "") return e.prop("textContent")
	return res
}

async function scrapeArticle(articleLink) {
	if (typeof articleLink != "string") throw Error("Article link is not a string")
	console.log("Scraping", articleLink, "...")
	// get article
	const text = await customFetch(articleLink)

	await fs.writeFile("./article.html", text)
	var doc = cheerio.load(text)

	// get header, author, and date first
	let articleTitle = (doc("h1.post-title").prop("textContent") ?? doc("h2.pencraft").prop("textContent"))
		?? doc("meta[property=\"og:title\"]").attr("content") // various places from where to get the title
	if (articleTitle === undefined) {
		throw Error("Undefined article title")
	}
	// subtitle is either h3.subtitle or a div adjacent to h2.pencraft
	let articleSubtitle = doc("h3.subtitle").prop("textContent") ?? doc("h2.pencraft").next().prop("textContent")
		?? doc("meta[property=\"description\"]").attr("content")
	let articleDate = undefined
	doc("div.pencraft").each(function () {
		if (articleDate != undefined) return
		const text = doc(this).prop("textContent").trim()
		if (text.length < 100 && DATE_RE.test(text)) {
			articleDate = DATE_RE.exec(text)[0]
		} else if (text.length < 100 && DATE_RE_2.test(text)) {
			articleDate = DATE_RE_2.exec(text)[0]
		}
	})

	// last resort
	if (articleDate === undefined) {
		const date = DATE_RE_3.exec(text)?.[1]
		if (typeof date == "string") {
			const formattedDate = (new Date(date)).toLocaleDateString("en-US", {month: "short", day: "numeric", year: "numeric"})
			articleDate = formattedDate
		}
	}

	if (articleDate === undefined) {
		throw Error("Article date is undefined!")
	}

	let parsedArticle = `Original URL: ${articleLink}\nScrape time: ${new Date()}\n\n# ${articleTitle}`
	if (articleSubtitle !== undefined) {
		parsedArticle += "\n## " + articleSubtitle
	}
	parsedArticle += "\n### " + articleDate

	// need a special API for these posts
	if (articleLink.startsWith("https://substack.com/home/post/p-")) {
		const apiResp = await customFetch("https://substack.com/api/v1/posts/by-id/" + articleLink.slice(33))
		const actualArticleText = JSON.parse(apiResp).post.body_html
		doc = cheerio.load("<div class='body markup'>" + actualArticleText + "</div>")
	} else {
		let scriptBody = null
		const scripts = doc("script")
		for (var i = 0; i < scripts.length; i++) {
			const tc = doc(scripts[i]).prop("textContent")
			if (tc.startsWith("window._preloads")) {
				let actualArticleText = tc.slice(38, -2).replaceAll("\\\"","\"").replaceAll("\\\\","\\")
				actualArticleText = JSON.parse(actualArticleText)?.post?.body_html
				if (actualArticleText != undefined) doc = cheerio.load("<div class='body markup'>" + actualArticleText + "</div>")
				break
			}
		}
	}

	const articleElements = doc("div.body.markup > *")

	// parse article body
	for (var i = 0; i < articleElements.length; i++) {
		const e = doc(articleElements[i])
		const tn = e.prop("tagName")
		if (tn == "P") { // normal paragraph
			parsedArticle += "\n\n" + parseInnerHtmlToMd(doc, e)
		} else if (tn == "UL" || tn == "OL") { // unordered list
			const bulletPoints = e.children()
			parsedArticle += "\n"
			for (var ii = 0; ii < bulletPoints.length; ii++) {
				parsedArticle += `\n${tn == "UL" ? "*" : (ii+1)+"."} ` + parseInnerHtmlToMd(doc, doc(bulletPoints[ii])).trim()
			}
		} else if (tn.startsWith("H") && tn.length == 2) { // header
			const num = Number(tn.split("")[1])
			if (isNaN(num) || num > 6) {
				console.error("Weird header tag! ", tn)
				throw Error("Weird header tag!")
			}
			parsedArticle += `\n\n${Array(num).fill("#").join("")} ${parseInnerHtmlToMd(doc, e)}`
		} else if ((tn == "DIV" && e.attr("class") == "pullquote" ) || tn == "BLOCKQUOTE") {
			parsedArticle += `\n\n> ${parseInnerHtmlToMd(doc, doc(e.children()[0]))}`
		} else if (tn == "PRE") {
			parsedArticle += `\n\n\`\`\`\n${e.prop("textContent")}\n\`\`\``
		} else if (tn == "SPAN") {
			parsedArticle += e.prop("textContent")
		} else if (tn == "A") {
			parsedArticle += "[" + parseInnerHtmlToMd(doc, e) + "](" + e.attr("href") + ")"
		} else if (!["DIV", "IFRAME"].includes(tn)) { // some ignored tags
			console.log(e.prop("textContent"))
			throw Error("Unknown tag name: " + tn)
		}
	}

	await fs.writeFile("./article-parsed.md", parsedArticle)

	return parsedArticle
}

// article id is just authorSubdomain/article-slug
async function saveArticleToDisk(articleId, articleMarkdown) {
	const artHash = sha256(articleId)
	const artDir = `./data/${artHash[0]}/${artHash[1]}`
	const exists = await fs.stat(artDir).catch(e => false)
	if (!exists) {
		await fs.mkdir(artDir, { recursive: true })
	}
	await fs.writeFile(`${DATA_DIRECTORY_PARENT}${artDir}/${artHash}.md`, articleMarkdown)
}

async function getArticlesFromAuthor(authorSubdomain, maxLimit) {
	if (typeof authorSubdomain != "string") throw Error("authorSubdomain is not a string")
	if (typeof maxLimit != "number") throw Error("maxLimit is not a number")

	console.log("Getting articles from", authorSubdomain, "...")
	const articles = new Set()
	for (var offset = 0; articles.size < maxLimit && offset < 300; offset += 20) {
		const text = await customFetch(`https://${authorSubdomain}.substack.com/api/v1/archive?sort=new&search=&offset=${offset}&limit=20`)
		// if (text.length < 100) return undefined // some private substacks return some short 403 error
		await millis(750)
		const json = JSON.parse(text)
		if (json.length == 0) break
		const newArticles = json.filter(p => p.audience == "everyone" && p.type != "podcast").map(i => i.canonical_url)
		// add new articles to set
		for (var i = 0; i < newArticles.length && articles.size < maxLimit; i++) {
			articles.add(newArticles[i])
		}
	}
	return Array.from(articles)
}

async function downloadArticlesFromAuthor(authorSubdomain, maxLimit, skipExisting = false) {
	const articles = await getArticlesFromAuthor(authorSubdomain, maxLimit)
	for (var i = 0; i < articles.length; i++) {
		const articleLink = articles[i]
		const artId = authorSubdomain + "/" + articleLink.split(ARTICLE_NAME_RE)[1]
		if (skipExisting) {
			const artHash = sha256(artId)
			const fileExists = await fs.access(`./data/${artHash[0]}/${artHash[1]}/${artHash}.md`).catch(() => false)
			if (fileExists !== false) {
				console.log("Skipping article", artId, "because it exists already")
				continue
			}
		}
		const articleMd = await scrapeArticle(articleLink)
		await saveArticleToDisk(artId, articleMd)
		await millis(750)
	}
}

async function getRisingSubdomains(categoryId) {
	console.log("Getting rising authors for category", categoryId, "...")

	const authors = new Set()

	for (var page = 0; true; page++) {
		const text = await customFetch(`https://substack.com/api/v1/category/leaderboard/${categoryId}/trending?page=${page}`)
		const json = JSON.parse(text)
		const newAuthors = json.items.filter(i => i.publication.language == "en").map(i => i.publication.subdomain)
		for (var i = 0 ; i < newAuthors.length; i++) {
			authors.add(newAuthors[i])
		}
		if (!json.more) break
	}

	return Array.from(authors)
}

async function downloadArticlesByCategory(categoryId, maxLimitPerAuthor) {
	const authors = await getRisingSubdomains(categoryId)
	for (var i = 0; i < authors.length; i++) {
		await downloadArticlesFromAuthor(authors[i], maxLimitPerAuthor, true)
	}
}

// gets articles from disk
async function getTopArticlesByKeyword(keywords, articleCount) {
	if (!Array.isArray(keywords) || isNaN(articleCount)) throw Error("Incorrect argument value")
	const startTime = Date.now()
	keywords = keywords.map(k => k.toLowerCase())
	const totalKeywordCt = keywords.length
	const topArticles = Array(articleCount).fill('{"score":0}').map(JSON.parse)
	for (var i = 0; i < 256; i++) {
		const b16 = i.toString("16").padStart(2, "0")
		const files = await fs.readdir(`./data/${b16[0]}/${b16[1]}`)
		for (var ii = 0; ii < files.length; ii++) {
			const fulltext = (await fs.readFile(`./data/${b16[0]}/${b16[1]}/${files[ii]}`, "utf8")).toLowerCase()
			let keywordInclusionCt = 0 // how many of the keywords are in article
			let thisArticleScore = 0
			for (iii = 0; iii < keywords.length; iii++) {
				const k = keywords[iii]
				const kCount = fulltext.split(k).length - 1
				if (kCount == 0) continue
				keywordInclusionCt ++
				// article score is exponential
				thisArticleScore += kCount * (2**(k.length))
			}
			thisArticleScore *= ((keywordInclusionCt / totalKeywordCt)**2) / Math.sqrt(fulltext.length)

			// if it has a score better than the worst top article, add it in the array and recompute
			if (thisArticleScore > topArticles[articleCount-1].score) {
				topArticles.push({
					title: fulltext.slice(fulltext.indexOf("#")+2, fulltext.indexOf("##")-1),
					file: files[ii],
					score: thisArticleScore
				})
				topArticles.sort((a, b) => b.score - a.score)
				topArticles.pop()
			}
		}
	}
	const endTime = Date.now()
	console.log("Keyword search took", endTime - startTime, "ms")
	return topArticles
}

async function downloadSelectedCategories(maxLimitPerAuthor) {
	const catText = await fs.readFile("./categories.txt", "utf8")
	const cats = catText.split("\n")
		.map(l => {
			if (!isNaN(l)) return Number(l)
			const spaceIndex = l.indexOf(" ")
			if (spaceIndex > 0) return Number(l.slice(0, spaceIndex))
			return undefined
		})
		.filter(n => n % 1 == 0 && n > 0)
	for (var i = 0; i < cats.length; i++) {
		await downloadArticlesByCategory(cats[i], maxLimitPerAuthor)
	}
}

const target = process.argv[2]
const arg = process.argv[3]

if (target == "article") {
	if (typeof arg != "string") {
		throw Error("Article URL must be a string")
	}
	scrapeArticle(arg).then(async md => {
		await fs.writeFile("./article.md", md)
		console.log("Saved article to ./article.md")
	})
} else if (target == "authorSubdomain") {
	if (typeof arg != "string") {
		throw Error("Author subdomain must be a string")
	}
	downloadArticlesFromAuthor(arg, 50, true)
} else if (target == "category") {
	if (isNaN(arg)) {
		throw Error("Category number must be a number")
	}
	downloadArticlesByCategory(Number(arg), 50)
} else if (target == "all") {
	downloadSelectedCategories(50)
}
