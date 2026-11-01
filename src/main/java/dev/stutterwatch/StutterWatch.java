package dev.stutterwatch;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StutterWatch: finds out WHY your game stutters.
 *
 * Every client tick should be ~50 ms apart. If the main thread stalls (GC pause,
 * chunk upload, shader compile, disk IO, CPU thermal throttling...) the gap
 * between ticks grows. We measure that gap and check how much JVM garbage
 * collection time happened during it, then log a verdict to logs/latest.log.
 *
 * Uses only Fabric API + JDK APIs (no mixins), so it should survive Minecraft updates.
 */
public class StutterWatch implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stutterwatch");

    /** A tick gap longer than this counts as a stutter (nominal gap is 50 ms). */
    private static final long SPIKE_NS = 120_000_000L;
    /** Ignore the first ticks after joining a world: chunk loading makes big gaps that are normal. */
    private static final int WARMUP_TICKS = 200;

    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private long lastTickNs;
    private long lastGcMs;
    private int warmup;

    private int spikes;
    private int gcSpikes;
    private long worstMs;

    @Override
    public void onInitializeClient() {
        LOG.info("StutterWatch active. Collectors: {}", gcBeans.stream().map(GarbageCollectorMXBean::getName).toList());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastTickNs = 0;
            warmup = WARMUP_TICKS;
            lastGcMs = totalGcMs();
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> onTick());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (spikes > 0) {
                LOG.info("Session summary: {} stutters (>{} ms), {} caused by GC, worst {} ms.",
                        spikes, SPIKE_NS / 1_000_000L, gcSpikes, worstMs);
                if (gcSpikes * 2 >= spikes) {
                    LOG.info("Most stutters are GC pauses -> fix JVM args (fixed heap, ZGC/G1 tuning) or reduce allocation-heavy mods.");
                } else {
                    LOG.info("Most stutters are NOT GC -> look at CPU thermal throttling, chunk loading/render distance, disk IO, shader compile.");
                }
            }
        });
    }

    private void onTick() {
        long now = System.nanoTime();
        if (warmup > 0) {
            warmup--;
            lastTickNs = now;
            return;
        }
        if (lastTickNs != 0) {
            long gap = now - lastTickNs;
            if (gap > SPIKE_NS) {
                report(gap);
            }
        }
        lastTickNs = now;
        lastGcMs = totalGcMs();
    }

    private void report(long gapNs) {
        long gapMs = gapNs / 1_000_000L;
        long gcMs = totalGcMs() - lastGcMs;
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long usedMb = heap.getUsed() >> 20;
        long maxMb = heap.getMax() >> 20;

        spikes++;
        worstMs = Math.max(worstMs, gapMs);

        String verdict;
        if (gcMs >= gapMs / 2) {
            gcSpikes++;
            verdict = "GC PAUSE";
        } else if (usedMb * 100 / Math.max(1, maxMb) > 90) {
            verdict = "heap nearly full (GC pressure / allocation too low)";
        } else {
            verdict = "non-GC stall (chunk upload, shader compile, disk IO, or CPU throttling)";
        }
        LOG.warn("Stutter {} ms | GC during stall: {} ms | heap {}/{} MB | {}", gapMs, gcMs, usedMb, maxMb, verdict);
    }

    private long totalGcMs() {
        long total = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            long t = bean.getCollectionTime();
            if (t > 0) {
                total += t;
            }
        }
        return total;
    }
}
