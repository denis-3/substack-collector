# Substack Article Collector

A simple (soon-to-be) app for downloading and storing articles in markdown. This project is currently being re-written in Kotlin; check the `main` branch for the previous NodeJS implementation.

## How to Run

1. Download [OpenJDK 24](https://jdk.java.net/24/), [Kotlin](https://kotlinlang.org/docs/command-line.html), and the [Gradle build system](https://gradle.org/install/).
2. `cd` into the root of this repo.
3. Run `gradle run`. For now, this will simply test some of the functions in `src/SubstackLogic.kt` and `src/ArticleFetcher.kt`.

## Dependencies

This project currently depends on
* `kotlinx-coroutines-core v1.10.2` for coroutines and
* `jsoup v1.21.2` for parsing HTML.

In general, dependencies will be kept to a minimum to reduce code complexity and allow for deploying on resource-constrained systems, like embedded computers.
