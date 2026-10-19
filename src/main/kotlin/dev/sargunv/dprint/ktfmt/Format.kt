package dev.sargunv.dprint.ktfmt

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlinx.ktfmt.format.FileType
import org.jetbrains.kotlinx.ktfmt.format.Formatter
import org.jetbrains.kotlinx.ktfmt.format.KotlinCode

/**
 * Returns the formatted text, or null when nothing changed. Throws ktfmt's ParseError on invalid
 * input.
 */
fun formatKotlin(path: String, text: String, config: ResolvedConfig): String? {
  val fileType = if (path.endsWith(".kts")) FileType.SCRIPT else FileType.REGULAR
  // ktfmt keeps the input's first line ending, which is what "auto" means.
  val formatted = Formatter.format(config.options, KotlinCode.from(text, fileType))
  val output =
    when (config.newLineKind) {
      NewLineKind.AUTO -> formatted
      NewLineKind.LF -> formatted.withLineEndings("\n")
      NewLineKind.CRLF -> formatted.withLineEndings("\r\n")
      NewLineKind.SYSTEM -> formatted.withLineEndings(System.lineSeparator())
    }
  return output.takeIf { it != text }
}

private fun String.withLineEndings(separator: String) = lineSequence().joinToString(separator)

// Worker isolates share no objects with the main one, so the config arrives as its resolved JSON
// and is parsed once per isolate.
private val workerConfigs = ConcurrentHashMap<String, ResolvedConfig>()

/**
 * [formatKotlin] as the worker isolates in [dev.sargunv.dprint.ktfmt.isolates.IsolatePool] run it.
 */
fun formatInWorker(path: String, configJson: String, text: ByteArray): ByteArray? {
  val config = workerConfigs.getOrPut(configJson) { resolveConfig("{}", configJson) }
  return formatKotlin(path, text.decodeToString(), config)?.encodeToByteArray()
}
