package io.testforge.kafka;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;

public class KafkaPollingCollector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaPollingCollector.class);

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaProbeProperties properties;
    private final KafkaMessageBuffer buffer;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread worker;
    private volatile Consumer<String, String> consumer;

    public KafkaPollingCollector(
            ConsumerFactory<String, String> consumerFactory,
            KafkaProbeProperties properties,
            KafkaMessageBuffer buffer) {
        this.consumerFactory = consumerFactory;
        this.properties = properties;
        this.buffer = buffer;
    }

    @Override
    public void start() {
        if (properties.topics().isEmpty()) {
            log.warn("Kafka probe is enabled but forge.kafka.topics is empty; collector will not start");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        worker = new Thread(this::pollLoop, "testforge-kafka-probe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Consumer<String, String> currentConsumer = consumer;
        if (currentConsumer != null) {
            currentConsumer.wakeup();
        }
        Thread currentWorker = worker;
        if (currentWorker != null) {
            try {
                currentWorker.join(properties.pollTimeout().multipliedBy(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void pollLoop() {
        try (Consumer<String, String> createdConsumer = consumerFactory.createConsumer()) {
            consumer = createdConsumer;
            createdConsumer.subscribe(properties.topics());
            if (properties.seekToBeginningOnStart()) {
                // the first poll() can return before the group rebalance has
                // assigned partitions — keep polling until the assignment lands
                for (int attempt = 0; attempt < 20 && createdConsumer.assignment().isEmpty(); attempt++) {
                    createdConsumer.poll(properties.pollTimeout());
                }
                if (createdConsumer.assignment().isEmpty()) {
                    log.warn("No partitions assigned after rebalance wait; seekToBeginning skipped");
                } else {
                    createdConsumer.seekToBeginning(createdConsumer.assignment());
                }
            }

            while (running.get()) {
                ConsumerRecords<String, String> records = createdConsumer.poll(properties.pollTimeout());
                records.forEach(record -> buffer.append(KafkaMessage.from(record)));
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } catch (KafkaException e) {
            log.error("Kafka probe collector stopped after Kafka error", e);
        } catch (RuntimeException e) {
            log.error("Kafka probe collector stopped unexpectedly", e);
        } finally {
            consumer = null;
            running.set(false);
        }
    }
}
