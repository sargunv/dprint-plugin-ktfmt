plugins {
  kotlin("jvm") version "2.4.10"
  application
  id("org.graalvm.buildtools.native") version "1.1.8"
}

group = "dev.sargunv"

repositories { mavenCentral() }

dependencies {
  testImplementation(kotlin("test"))
  implementation("org.jetbrains.kotlinx:ktfmt:0.65")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  // For the @Substitute in src/main/java (copied from ktfmt's native-image source set).
  compileOnly("org.graalvm.nativeimage:svm:25.0.1")
  // kotlin-compiler-embeddable ships jline's native-image.properties without the configs they
  // point at; ktfmt's own native build supplies the real jars the same way.
  listOf("jline-terminal", "jline-terminal-jansi", "jline-terminal-jna", "jline-terminal-jni")
    .forEach {
      nativeImageClasspath("org.jline:$it:3.30.6")
    }
}

kotlin { jvmToolchain(25) }

application { mainClass = "dev.sargunv.dprint.ktfmt.MainKt" }

fun lines(name: String) =
  file("src/main/native-image/$name")
    .readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }

graalvmNative {
  toolchainDetection = false
  // GraalVM CE 25.0.2 rejects the repository's conditional bulk reflection queries; ktfmt's own
  // metadata in src/main/resources covers everything the formatter needs.
  metadataRepository { enabled = false }
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
