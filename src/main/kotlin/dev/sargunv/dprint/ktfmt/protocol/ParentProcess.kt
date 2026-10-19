package dev.sargunv.dprint.ktfmt.protocol

import kotlin.system.exitProcess

/** The dprint CLI passes `--parent-pid <pid>`; the plugin should exit once that process is gone. */
fun parentProcessId(args: Array<String>): Long? =
  args.indexOf("--parent-pid").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }

fun startParentProcessChecker(pid: Long) {
  Thread(
      {
        while (true) {
          Thread.sleep(5_000)
          if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) exitProcess(1)
        }
      },
      "parent-process-checker",
    )
    .apply { isDaemon = true }
    .start()
}
