plugins {
	kotlin("jvm") version "2.2.10"
	application
	java
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(24)
	}
}

kotlin {
	compilerOptions {
		allWarningsAsErrors = true
		extraWarnings = true
		freeCompilerArgs = listOf("-Xuse-fir-lt=false")
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

sourceSets.main {
	java.setSrcDirs(listOf("./src"))
}

application {
	mainClass = "K_stackKt"
	applicationDefaultJvmArgs = listOf("-Dkotlinx.coroutines.debug")
}
