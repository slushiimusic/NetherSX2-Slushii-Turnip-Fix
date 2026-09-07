package xyz.aethersx2.android.shim;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CapturePausesTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        AtomicInteger nativeMask = new AtomicInteger();
        AtomicLong time = new AtomicLong(100);
        CapturePauses p = new CapturePauses(nativeMask::set, time::get);
        CapturePauses.Lease ir = p.acquire(CapturePauses.Reason.IR_CHANGE, "resolution", 5000);
        CapturePauses.Lease init = p.acquire(CapturePauses.Reason.PIPELINE_START, "init", 5000);
        init.close();
        check(nativeMask.get() == 1, "init completion must not resume an IR change");
        CapturePauses.Lease bind = p.acquire(CapturePauses.Reason.OUTPUT_BIND, "bind", 5000);
        ir.close();
        check(nativeMask.get() == 8, "GS settle must not resume a queued output bind");
        CapturePauses.Lease nextBind = p.acquire(CapturePauses.Reason.OUTPUT_BIND, "next bind", 5000);
        bind.close(); bind.close();
        check(nativeMask.get() == 8, "stale/double close must not release a newer same-reason operation");
        time.set(6000);
        check(p.overdue().contains("OUTPUT_BIND/next bind"), "deadlines identify the owner");
        check(nativeMask.get() == 8, "deadline must not resume a native worker still using its window");
        nextBind.close();
        check(nativeMask.get() == 0, "last owner resumes capture");
        for (int round = 0; round < 100; round++) {
            CountDownLatch acquired = new CountDownLatch(2), release = new CountDownLatch(1);
            Runnable task = () -> {
                try (CapturePauses.Lease lease = p.acquire(CapturePauses.Reason.CONTEXT_REBUILD, "worker", 5000)) {
                    acquired.countDown(); release.await();
                } catch (InterruptedException e) { throw new RuntimeException(e); }
            };
            Thread a = new Thread(task), b = new Thread(task);
            a.start(); b.start(); acquired.await();
            check(nativeMask.get() == 4, "concurrent teardown owners must pause");
            release.countDown(); a.join(); b.join();
            check(nativeMask.get() == 0, "concurrent teardown owners must release exactly once each");
        }
        java.lang.reflect.Method soc = HandheldTier.class.getDeclaredMethod("tierFromSocModel", String[].class);
        soc.setAccessible(true);
        check((int)soc.invoke(null, (Object)new String[]{"QCS8550"}) == 2, "Nova QCS8550 high tier");
        check((int)soc.invoke(null, (Object)new String[]{"SM8550"}) == 2, "Thor 8 Gen 2 high tier");
        check((int)soc.invoke(null, (Object)new String[]{"SM8250"}) == 1, "Thor Lite SD865 low tier");
        check((int)soc.invoke(null, (Object)new String[]{"QCS6490"}) == 0, "unknown IoT part needs GPU evidence");
        check(!ShimRestartPrompt.gameRunning(), "unresolved NativeLibrary should report unavailable");
        java.nio.file.Path stub = java.nio.file.Paths.get(args[0], "NativeLibrary.java");
        java.nio.file.Files.writeString(stub, "package xyz.aethersx2.android; public class NativeLibrary {"
                + " public static boolean hasEmulationThread() { return true; } }");
        int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", args[0], stub.toString());
        check(compiled == 0, "compile late-loading emulator API");
        check(ShimRestartPrompt.gameRunning(), "reflection lookup must retry after the API becomes available");
        System.out.println("PASS: pause ownership, duplicate completion, deadlines, 100 concurrent races, Nova/Thor/Thor Lite classification, reflection recovery");
    }
}
