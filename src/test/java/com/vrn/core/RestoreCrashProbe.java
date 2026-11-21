package com.vrn.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Only in test output, never the executable JAR: faults/hard process exits for packaged replay. */
public final class RestoreCrashProbe {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); String mode=args[1],selector=args[2]; int step=Integer.parseInt(args[3]);
        AtomicInteger mutations=new AtomicInteger();
        var ops=new RestoreFileOps(root) {
            @Override protected void checkpoint(Event event,String detail) throws IOException {
                boolean trigger=mode.equals("halt-prepared")?event==Event.JOURNAL_WRITE && detail.equals("APPLYING:0:false")
                        :mode.equals("halt-complete")?event==Event.CLEANUP && detail.equals("COMPLETE")
                        :event==Event.AFTER_STEP && mutations.incrementAndGet()==step;
                if(trigger) {
                    if(mode.startsWith("halt")) Runtime.getRuntime().halt(77);
                    throw new IOException("Packaged deterministic execution failure");
                }
            }
        };
        new RestoreService(root,ops).restore(selector);
    }
}
