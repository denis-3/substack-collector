# Substack Article Collector

A simple Kotlin app for downloading, storing, and indexing Substack articles in markdown.

## How to Run

1. Download [OpenJDK 24](https://jdk.java.net/24/), [Kotlin](https://kotlinlang.org/docs/command-line.html), and the [Gradle build system](https://gradle.org/install/).
2. `cd` into the root of this repo.
3. Make sure to have a valid file at `resources/categories.txt`. You can copy the default one at `/resources/categories-sample.txt` to get started quickly, or alternatively make your own.
4. Run `gradle run`. This will run the webserver on port `6237` by default. To visit the server's webpage, go to `localhost:6237` in your browser.

*Note:* To run the scraping CLI instead, use `gradle run -PrunScrape --args "<your args here>"`, making sure to replace `<your args here>` with valid CLI arguments for the tool. Run `gradle run -PrunScrape --args help` for help on that.

## Dependencies

This project currently depends on
* `kotlinx-coroutines-core v1.10.2` for coroutines,
* `jsoup v1.21.2` for parsing HTML, and
* `json v20250517` for parsing JSON.

In general, dependencies will be kept to a minimum to reduce code complexity and allow for deploying on resource-constrained systems, like embedded computers.
