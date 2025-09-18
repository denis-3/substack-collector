plugins {
	kotlin("jvm") version "2.2.10"
	application
	java
}


kotlin {
	compilerOptions {
		// allWarningsAsErrors = true
		extraWarnings = true
		freeCompilerArgs = listOf("-Xuse-fir-lt=false")
	}
	jvmToolchain(24)
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
	implementation("org.jsoup:jsoup:1.21.2")
	implementation("org.json:json:20250517")
}

sourceSets.main {
	java.srcDir("src")
	resources.srcDir("resources")
}

application {
	// The default main class is the webserver
	mainClass = "WebserverKt"
	applicationDefaultJvmArgs = listOf("-Dkotlinx.coroutines.debug")
}

tasks.register<Tar>("packTar") {
	// Generate the build artifacts first
	dependsOn("installDist")

	// Pack resources and build to a single tar
	archiveFileName = "s-collector.tar"
	destinationDirectory = layout.buildDirectory.dir("distributions")

	from(layout.buildDirectory.dir("resources/main")) {
		into("s-collector/resources")
	}
	from(layout.buildDirectory.dir("install/substack-collector")) {
		into("s-collector")
		eachFile {
			if (name == "s-collector") {
				permissions { user { execute = true } }
			}
		}
	}
}

tasks.register("runScrape") {
	// Change the entrypoint of the application if the CLI scraping functionality is requested
	application.mainClass = "substacklogic.SubstackLogicKt"
	dependsOn("run")
}

tasks.register("buildScrape") {
	// Change the entrypoint of the application if the CLI scraping functionality is requested
	application.mainClass = "substacklogic.SubstackLogicKt"
	dependsOn("build")
}
