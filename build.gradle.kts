plugins {
  kotlin("jvm") version "2.4.10"
  id("org.graalvm.buildtools.native") version "1.1.8"
}

// A release build runs on its bare semver tag, which is the version; other builds report 0.0.0.
// scripts/lib.sh reads it the same way.
version =
  providers
    .exec {
      commandLine("git", "describe", "--tags", "--exact-match", "--match", "[0-9]*.[0-9]*.[0-9]*")
      isIgnoreExitValue = true
    }
    .standardOutput
    .asText
    .get()
    .trim()
    .ifEmpty { "0.0.0" }

repositories { mavenCentral() }

dependencies {
  testImplementation(kotlin("test"))
  implementation("org.jetbrains.kotlinx:ktfmt:0.65")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  // native-image APIs for src/main/java.
  compileOnly("org.graalvm.nativeimage:svm:25.0.1")
  // kotlin-compiler-embeddable ships jline's native-image.properties without the configs they
  // point at; ktfmt's own native build supplies the real jars the same way.
  listOf("jline-terminal", "jline-terminal-jansi", "jline-terminal-jna", "jline-terminal-jni")
    .forEach {
      nativeImageClasspath("org.jline:$it:3.30.6")
    }
}

kotlin { jvmToolchain(25) }

fun lines(name: String) =
  file("src/main/native-image/$name")
    .readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }

graalvmNative {
  toolchainDetection = false
  binaries.named("main") {
    imageName = "dprint-plugin-ktfmt"
    mainClass = "dev.sargunv.dprint.ktfmt.MainKt"
    // Mirrors ktfmt's own native-image flags (buildSrc/.../ktfmt.native-image.gradle.kts).
    buildArgs.addAll(
      "-O3",
      "-march=compatibility",
      "--no-fallback",
      "--future-defaults=all",
      "--link-at-build-time=org.jetbrains.kotlinx.ktfmt",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "-H:+ReportExceptionStackTraces",
      "-H:-UseContainerSupport",
      "-J--enable-native-access=ALL-UNNAMED",
      "-J--sun-misc-unsafe-memory-access=allow",
      "--initialize-at-build-time=dev.sargunv.dprint.ktfmt",
    )
    buildArgs.addAll(lines("initialize-at-build-time.txt").map { "--initialize-at-build-time=$it" })
    buildArgs.addAll(lines("initialize-at-run-time.txt").map { "--initialize-at-run-time=$it" })
  }
}

tasks.processResources {
  from("LICENSE")
  inputs.property("version", version)
  filesMatching("plugin.properties") { expand("version" to version) }
}

tasks.test {
  useJUnitPlatform()
  // ConfigTest checks the schema against the resolved config.
  inputs.file("schema.json")
}
