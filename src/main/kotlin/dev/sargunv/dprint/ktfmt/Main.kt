package dev.sargunv.dprint.ktfmt

import dev.sargunv.dprint.ktfmt.protocol.PluginServer
import dev.sargunv.dprint.ktfmt.protocol.parentProcessId
import dev.sargunv.dprint.ktfmt.protocol.startParentProcessChecker
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  // stdout carries the protocol; anything else printed there would corrupt it.
  val protocolOut = FileOutputStream(FileDescriptor.out)
  System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))

  parentProcessId(args)?.let(::startParentProcessChecker)

  val server = PluginServer(FileInputStream(FileDescriptor.`in`), protocolOut, KtfmtHandler)
  exitProcess(server.run())
}
