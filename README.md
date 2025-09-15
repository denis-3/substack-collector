# Substack Article Collector

A simple (soon-to-be) app for downloading and storing articles in markdown. This project is currently being re-written in Kotlin; check the `main` branch for the previous NodeJS implementation.

## How to Run

1. Download [OpenJDK 24](https://jdk.java.net/24/), [Kotlin](https://kotlinlang.org/docs/command-line.html), and the [Gradle build system](https://gradle.org/install/).
2. `cd` into the root of this repo.
3. Make sure to have a valid file at `resources/categories.txt`. You can copy the default `/resources/categories-sample.txt` one or make your own.
4. Run `gradle run`. This will run the webserver on port `6237` by default. To visit the server's webpage, go to `localhost:6237` in your browser.

*Note:* To run the scraping CLI instead, use `gradle runScrape --args "<your args here>"`, making sure to replace `<your args here>` with valid CLI arguments for the tool. Run `gradle runScrape --args "help"` for help on that.

## Dependencies

This project currently depends on
* `kotlinx-coroutines-core v1.10.2` for coroutines,
* `jsoup v1.21.2` for parsing HTML, and
* `json v20250517` for parsing JSON.

In general, dependencies will be kept to a minimum to reduce code complexity and allow for deploying on resource-constrained systems, like embedded computers.
