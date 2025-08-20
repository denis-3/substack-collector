const http2 = require("http2")
const zlib = require("zlib")

const HEADERS = {
	"User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0",
	"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
	"Accept-Language": "en-US,en;q=0.5",
	"Accept-Encoding": "gzip",
	"Sec-GPC": "1",
	"Upgrade-Insecure-Requests": "1",
	"Sec-Fetch-Dest": "document",
	"Sec-Fetch-Mode": "navigate",
	"Sec-Fetch-Site": "none",
	"Sec-Fetch-User": "?1",
	"Priority": "u=0, i",
	"TE": "trailers"
}

const USER_AGENTS = [
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.10 Safari/605.1.1",
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.3",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3",
	"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.3",
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 Trailer/93.3.8652.5"
]

const cookies = new Map()

async function millis(m) {
	return new Promise(function (resolve, reject) {
		setTimeout(function () {
			resolve()
		}, m)
	})
}

function http2Fetch(url, headers = {}, redirectDepth = 0) {
	return new Promise((resolve, reject) => {
		const { hostname, pathname, protocol, search: queryStr, hash: urlFragment } = new URL(url);

		if (protocol !== "https:") throw Error("No HTTP URLs")

		const client = http2.connect(url);
		const requestHeaders = { ":path": pathname + urlFragment + queryStr, ...headers };
		const req = client.request(requestHeaders);

		let data = "";
		let status = null
		const responseHeaders = {};
		var setCookie = null
		var compressedResponse = false

		req.on("response", (responseHeaders) => {
			var redirectLocation = null
			Object.entries(responseHeaders).forEach(([key, value]) => {
				responseHeaders[key] = value;
				if (key.toLowerCase() == "set-cookie") {
					setCookie = value
				} else if (key.toLowerCase() == "content-encoding") {
					if (value != "gzip") {
						console.error(responseHeaders)
						throw Error("Unknown compression: " + value)
					}
					compressedResponse = true
				} else if (key.toLowerCase() == "location") {
					redirectLocation = value
				}
			});

			status = responseHeaders[':status'];

			// handle redirect
			if (300 < status && status < 400 && redirectDepth < 5) {
				if (!redirectLocation) throw Error("Got a redirect status code but no redirect location")
				const redirectUrl = (new URL(redirectLocation, url)).toString()
				http2Fetch(redirectUrl, headers, redirectDepth+1).then(resolve)
				return
			} else if (300 < status && status < 400 && redirectDepth >= 5) {
				throw Error("Reached redirect level 5 with another redirect! Something is wrong...")
			}

			if (compressedResponse) {
				let gunzip = zlib.createGunzip()
				req.pipe(gunzip)

				gunzip.on("data", chunk => {
					data += chunk.toString();
				});

				gunzip.on("end", () => {
					resolve({ text: data, headers: responseHeaders, status, setCookie });
				})
			} else {
				req.on("data", chunk => {
					data += chunk.toString()
				})

				req.on("end", () => {
					resolve({ text: data, headers: responseHeaders, status, setCookie });
				})
			}
		});

		req.on("end", () => {
			client.close();
		});

		req.on("error", (err) => {
			client.close();
			console.error(err)
			throw Error("HTTP2 error!")
		});

		req.end();
	});
}


const customFetch = async function (url, retry = true) {
	const theseHeaders = JSON.parse(JSON.stringify(HEADERS))
	let cookieStr = ""
	cookies.forEach((value, key) => {
		if (value.expiration > Date.now()) {
			return cookies.delete(key)
		}
		if (value.sameSite != "None" && !url.includes(value.domain)) return
		cookieStr += `${key}=${value.value}; `
	})
	if (cookieStr != "") theseHeaders["Cookie"] = cookieStr.trim()
	const req = await http2Fetch(url, theseHeaders)
	const text = req.text
	if (req.status === 429 && retry) {
		console.error("Got a 429 status code! Retrying in 10s with new cookies...")
		cookies.clear()
		await millis(10000)
		return customFetch(url, false)
	} else if (req.status != 200 && req.status != 403) { // some private substacks return 403
		console.error(text)
		throw Error(`Non-successful status code [${url}]: ` + String(req.status))
	}
	const newCookies = req.setCookie
	for (var i = 0; i < newCookies.length; i++) {
		const parts = newCookies[i].split("; ")
		const [cName, cValue] = parts[0].split("=")
		const cookieData = {}
		for (var ii = 1; ii < parts.length; ii++) {
			const [key, value] = parts[ii].split("=")
			cookieData[key] = value != "" ? value : null
		}
		let cExp = undefined
		if (cookieData["Max-Age"] !== undefined) {
			cExp = Date.now() + Number(cookieData["Max-Age"]) * 1000
		} else if (cookieData["Expires"] !== undefined) {
			cExp = (new Date(cookieData["Expires"])).getTime()
		}
		cookies.set(cName, {
			value: cValue,
			expiration: cExp,
			domain: cookieData["domain"],
			sameSite: cookieData["SameSite"]
		})
	}
	return text
}

module.exports = customFetch
