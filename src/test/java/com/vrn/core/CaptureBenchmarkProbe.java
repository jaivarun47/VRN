package com.vrn.core;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone benchmark harness, usable with both the saved 8B JAR and the new JAR. */
public class CaptureBenchmarkProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path storage = root.resolve(".vrn");
        var running = new AtomicBoolean(true);
        var peak = new AtomicLong();
        var heap = ManagementFactory.getMemoryMXBean();
        Runnable sample = () -> peak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(), Math::max);
        Thread sampler = Thread.ofPlatform().daemon().start(() -> {
            while (running.get()) {
                sample.run();
                try { Thread.sleep(5); } catch (InterruptedException interrupted) { return; }
            }
        });
        var publications = new AtomicInteger();
        var publisher = new AtomicFilePublisher() {
            @Override protected void move(Path temporary, Path destination) throws IOException {
                if (destination.equals(storage.resolve("staging/index"))) publications.incrementAndGet();
                super.move(temporary, destination);
            }
        };
        long start = System.nanoTime();
        try {
            new AddService(root, new FileScanner(), new ObjectStore(storage), publisher).add(Path.of(args[1]));
        } finally {
            sample.run();
            running.set(false);
            sampler.join(1000);
            System.out.println("capture.ms=" + (System.nanoTime() - start) / 1_000_000);
            System.out.println("sampled.heap.peak.bytes=" + peak.get());
            System.out.println("heap.max.bytes=" + Runtime.getRuntime().maxMemory());
            System.out.println("index.publications=" + publications.get());
        }
        long verifyStart = System.nanoTime();
        for (var operation : new StagingArea(storage).readOperations().values()) {
            if (operation instanceof StagedOperation.Put put) new ObjectStore(storage).verify(put.contentHash());
        }
        System.out.println("verification.ms=" + (System.nanoTime() - verifyStart) / 1_000_000);
        try (var objects = Files.list(storage.resolve("objects"))) {
            System.out.println("object.count=" + objects.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}")).count());
        }
    }
}
