package com.nusrat.chargepoint.queue;

import com.nusrat.chargepoint.model.ChargePointEvent;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A small in-process pub-sub queue that mimics the one property of Kafka
 * this project actually relies on: events for the same charge point are
 * always handled in the order they were published, because they are
 * routed to the same worker by a hash of the charge point id - the same
 * idea as Kafka's "same partition key -> same partition -> ordered"
 * guarantee.
 *
 * This is NOT Kafka. There is no persistence, no replication, no
 * consumer groups across processes, and no durability if the JVM dies.
 * It exists so the ingest -> process -> metrics pipeline in this demo is
 * genuinely asynchronous and testable without a broker, in an
 * environment where I can't pull in a real Kafka client dependency.
 * Swapping this class for Spring Kafka's KafkaTemplate/@KafkaListener
 * is the next real step, not something this project pretends to have
 * already done.
 */
public final class InMemoryEventBus {

    private final int workerCount;
    private final List<BlockingQueue<ChargePointEvent>> partitions;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    public InMemoryEventBus(int workerCount) {
        this.workerCount = workerCount;
        this.partitions = new CopyOnWriteArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            partitions.add(new LinkedBlockingQueue<>());
        }
    }

    public void publish(ChargePointEvent event) {
        int partition = Math.floorMod(event.chargePointId().hashCode(), workerCount);
        partitions.get(partition).add(event);
    }

    public void subscribe(Consumer<ChargePointEvent> handler) {
        for (int i = 0; i < workerCount; i++) {
            BlockingQueue<ChargePointEvent> queue = partitions.get(i);
            Thread worker = new Thread(() -> runWorker(queue, handler), "event-bus-worker-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    private void runWorker(BlockingQueue<ChargePointEvent> queue, Consumer<ChargePointEvent> handler) {
        while (running) {
            try {
                ChargePointEvent event = queue.poll(500, TimeUnit.MILLISECONDS);
                if (event != null) {
                    handler.accept(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void shutdown() {
        running = false;
    }
}
