# Simple substack scraping tool
## Usage
Make sure to download the required dependencies using `npm i`. Then, you can run `node index [TARGET]` where `[TARGET]` is one of the following scraping targets:
* `article`: Scrape one article and save it to `./article.md`. Example: `node index article https://substack.com/home/post/p-168778797`
* `authorSubdomain`: Get articles (up to 50) from a particular author subdomain. Example: `node index worksinprogress` will get articles from `worksinprogress.substack.com` and save them in a data directory (configurable as a global variable in `index.js`)
* `category`: Get articles from the top 100 authors in a certain category. Example: `node index category 4` will get articles from the category with ID `4`, which is the technology category.
* `all`: Fetch all articles from the categories described in a `categories.txt` file.

More documentation to come soon...
