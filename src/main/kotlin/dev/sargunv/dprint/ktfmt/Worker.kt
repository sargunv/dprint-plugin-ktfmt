package dev.sargunv.dprint.ktfmt

import java.util.concurrent.ConcurrentHashMap

// Runs inside a worker isolate (see IsolatePool), which can't share objects with the main one, so
// the config arrives as its resolved JSON and is parsed once per isolate.
private val workerConfigs = ConcurrentHashMap<String, ResolvedConfig>()

fun formatInWorker(path: String, configJson: String, text: ByteArray): ByteArray? {
  val config = workerConfigs.getOrPut(configJson) { resolveConfig("{}", configJson) }
  return formatKotlin(path, text.decodeToString(), config)?.encodeToByteArray()
}
