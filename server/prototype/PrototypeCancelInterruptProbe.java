// Reproducible design probe. Not part of the production build.
// Question: does Future.cancel(true) / CompletableFuture.cancel(true) actually
// deliver Thread.interrupt() to a running task?
//
// Three probes:
//   1. CompletableFuture.supplyAsync + cancel(true)   -> expect: NO interrupt
//   2. ExecutorService.submit + Future.cancel(true)   -> expect: YES interrupt
//   3. Explicit promise + whenComplete forwarding     -> expect: YES interrupt
//
// VideoContextService uses probe 2's executor.submit + Future cancellation.
// Probe 3 documents an alternative when a caller must expose CompletableFuture.
// See docs/adr/0002-branch-degradation-and-timeout.md.
//
// Run: java server/prototype/PrototypeCancelInterruptProbe.java

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PrototypeCancelInterruptProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Probe 1: CompletableFuture.supplyAsync + cancel(true) ===");
        boolean p1 = probeSupplyAsync();

        System.out.println();
        System.out.println("=== Probe 2: ExecutorService.submit + Future.cancel(true) ===");
        boolean p2 = probeExecutorSubmit();

        System.out.println();
        System.out.println("=== Probe 3: Explicit promise + whenComplete forward ===");
        boolean p3 = probeExplicitPromiseForward();

        System.out.println();
        System.out.println("===== VERDICT =====");
        System.out.println("Probe 1 (supplyAsync.cancel):      " + (p1 ? "INTERRUPTED" : "NOT interrupted"));
        System.out.println("Probe 2 (executor.submit.cancel):  " + (p2 ? "INTERRUPTED" : "NOT interrupted"));
        System.out.println("Probe 3 (promise+whenComplete):    " + (p3 ? "INTERRUPTED" : "NOT interrupted"));
        System.out.println();
        if (!p1 && p2 && p3) {
            System.out.println("Cancellation assumptions VALIDATED:");
            System.out.println("  - supplyAsync.cancel does NOT interrupt (justifying executor.submit switch)");
            System.out.println("  - FutureTask.cancel DOES interrupt");
            System.out.println("  - whenComplete forwarding works");
        } else {
            throw new AssertionError("Cancellation assumptions changed; see probe output above.");
        }
    }

    static boolean probeSupplyAsync() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe1-worker");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean sleepCompleted = new AtomicBoolean(false);

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            System.out.println("  [worker] entered, sleeping 2s on " + Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
                sleepCompleted.set(true);
                return "completed normally";
            } catch (InterruptedException e) {
                interrupted.set(true);
                return "interrupted";
            }
        }, executor);

        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        boolean cancelled = future.cancel(true);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("  future.cancel(true) returned " + cancelled + " in " + elapsed + "ms");

        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("  executor terminated within 5s? " + terminated);
        System.out.println("  worker was interrupted? " + interrupted.get());
        System.out.println("  worker sleep completed? " + sleepCompleted.get());
        return interrupted.get();
    }

    static boolean probeExecutorSubmit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe2-worker");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean sleepCompleted = new AtomicBoolean(false);

        Future<String> future = executor.submit(() -> {
            System.out.println("  [worker] entered, sleeping 2s on " + Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
                sleepCompleted.set(true);
                return "completed normally";
            } catch (InterruptedException e) {
                interrupted.set(true);
                return "interrupted";
            }
        });

        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        boolean cancelled = future.cancel(true);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("  future.cancel(true) returned " + cancelled + " in " + elapsed + "ms");

        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("  executor terminated within 5s? " + terminated);
        System.out.println("  worker was interrupted? " + interrupted.get());
        System.out.println("  worker sleep completed? " + sleepCompleted.get());
        return interrupted.get();
    }

    static boolean probeExplicitPromiseForward() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe3-worker");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean sleepCompleted = new AtomicBoolean(false);

        CompletableFuture<String> promise = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> {
            System.out.println("  [worker] entered, sleeping 2s on " + Thread.currentThread().getName());
            try {
                Thread.sleep(2000);
                sleepCompleted.set(true);
                promise.complete("completed normally");
            } catch (InterruptedException e) {
                interrupted.set(true);
                promise.complete("interrupted");
            }
        });

        promise.whenComplete((result, ex) -> {
            if (ex instanceof CancellationException) {
                System.out.println("  [whenComplete] saw CancellationException on "
                        + Thread.currentThread().getName()
                        + ", forwarding to task.cancel(true)");
                task.cancel(true);
            }
        });

        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        boolean cancelled = promise.cancel(true);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("  promise.cancel(true) returned " + cancelled + " in " + elapsed + "ms");

        executor.shutdown();
        boolean terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("  executor terminated within 5s? " + terminated);
        System.out.println("  worker was interrupted? " + interrupted.get());
        System.out.println("  worker sleep completed? " + sleepCompleted.get());
        return interrupted.get();
    }
}
