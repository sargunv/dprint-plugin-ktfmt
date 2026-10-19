package dev.sargunv.dprint.ktfmt.isolates;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.sargunv.dprint.ktfmt.FormatKt;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.Isolates;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

/**
 * Formats in a pool of native-image isolates, each with its own heap and garbage collector, so one
 * isolate's stop-the-world collection doesn't pause formatting in the others. Native image only.
 */
public final class IsolatePool {
  private static final int CHANGED = 0;
  private static final int UNCHANGED = 1;
  private static final int FAILED = 2;

  // Raw Isolate pointers, created on first use. Threads are spread across them round-robin.
  private final long[] isolates;
  private final AtomicInteger nextSlot = new AtomicInteger();
  private final ThreadLocal<Long> workerThread = new ThreadLocal<>();

  public IsolatePool(int size) {
    isolates = new long[size];
  }

  /**
   * Formats [text] in this thread's isolate. Returns null when nothing changed, and throws with the
   * formatter's error message when it fails.
   */
  public byte[] format(String path, String configJson, byte[] text) {
    byte[] pathBytes = path.getBytes(UTF_8);
    byte[] configBytes = configJson.getBytes(UTF_8);
    CIntPointer kind = StackValue.get(CIntPointer.class);
    CIntPointer length = StackValue.get(CIntPointer.class);
    CCharPointer out;
    try (PinnedObject pinnedPath = PinnedObject.create(pathBytes);
        PinnedObject pinnedConfig = PinnedObject.create(configBytes);
        PinnedObject pinnedText = PinnedObject.create(text)) {
      out =
          formatInIsolate(
              worker(),
              pinnedPath.addressOfArrayElement(0),
              pathBytes.length,
              pinnedConfig.addressOfArrayElement(0),
              configBytes.length,
              pinnedText.addressOfArrayElement(0),
              text.length,
              kind,
              length);
    }
    if (kind.read() == UNCHANGED) return null;
    byte[] result = new byte[length.read()];
    CTypeConversion.asByteBuffer(out, result.length).get(result);
    UnmanagedMemory.free(out);
    if (kind.read() == FAILED) throw new RuntimeException(new String(result, UTF_8));
    return result;
  }

  private IsolateThread worker() {
    Long cached = workerThread.get();
    if (cached != null) return WordFactory.pointer(cached);
    int slot = Math.floorMod(nextSlot.getAndIncrement(), isolates.length);
    IsolateThread thread;
    synchronized (isolates) {
      if (isolates[slot] == 0) {
        thread = Isolates.createIsolate(Isolates.CreateIsolateParameters.getDefault());
        isolates[slot] = Isolates.getIsolate(thread).rawValue();
      } else {
        Isolate isolate = WordFactory.pointer(isolates[slot]);
        thread = Isolates.attachCurrentThread(isolate);
      }
    }
    workerThread.set(thread.rawValue());
    return thread;
  }

  @CEntryPoint
  private static CCharPointer formatInIsolate(
      @CEntryPoint.IsolateThreadContext IsolateThread thread,
      CCharPointer path,
      int pathLength,
      CCharPointer configJson,
      int configLength,
      CCharPointer text,
      int textLength,
      CIntPointer kind,
      CIntPointer length) {
    try {
      byte[] result =
          FormatKt.formatInWorker(
              new String(read(path, pathLength), UTF_8),
              new String(read(configJson, configLength), UTF_8),
              read(text, textLength));
      if (result == null) {
        kind.write(UNCHANGED);
        return WordFactory.nullPointer();
      }
      kind.write(CHANGED);
      return copyOut(result, length);
    } catch (Throwable t) {
      kind.write(FAILED);
      String message = t.getMessage() != null ? t.getMessage() : t.toString();
      return copyOut(message.getBytes(UTF_8), length);
    }
  }

  private static byte[] read(CCharPointer pointer, int length) {
    byte[] bytes = new byte[length];
    if (length > 0) CTypeConversion.asByteBuffer(pointer, length).get(bytes);
    return bytes;
  }

  private static CCharPointer copyOut(byte[] bytes, CIntPointer length) {
    CCharPointer out = UnmanagedMemory.malloc(Math.max(bytes.length, 1));
    CTypeConversion.asByteBuffer(out, bytes.length).put(bytes);
    length.write(bytes.length);
    return out;
  }
}
