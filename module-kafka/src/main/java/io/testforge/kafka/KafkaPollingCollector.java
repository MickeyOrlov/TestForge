package io.testforge.kafka;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private volatile CountDownLatch assigned = new CountDownLatch(1);

    static final int ASSIGNMENT_POLLS = 20;

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

        assigned = new CountDownLatch(1);
        worker = new Thread(this::pollLoop, "testforge-kafka-probe");
        worker.setDaemon(true);
        worker.start();
        // with auto-offset-reset=latest, a message sent before the partitions are
        // assigned is never seen: the first test must not start before that
        try {
            if (!assigned.await(properties.pollTimeout().multipliedBy(ASSIGNMENT_POLLS + 2).toMillis(),
                    TimeUnit.MILLISECONDS)) {
                log.warn("Kafka probe: no partitions of {} assigned yet; early messages may be missed",
                        properties.topics());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True once the consumer has partitions: messages sent from now on reach the buffer. */
    public boolean assigned() {
        return assigned.getCount() == 0;
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
        try (Consumer<String, String> createdConsumer = createConsumer()) {
            consumer = createdConsumer;
            createdConsumer.subscribe(properties.topics());
            // the first poll() can return before the group rebalance has
            // assigned partitions — keep polling until the assignment lands,
            // buffering whatever those polls already return
            for (int attempt = 0; attempt < ASSIGNMENT_POLLS && createdConsumer.assignment().isEmpty(); attempt++) {
                createdConsumer.poll(properties.pollTimeout())
                        .forEach(record -> buffer.append(KafkaMessage.from(record)));
            }
            if (createdConsumer.assignment().isEmpty()) {
                log.warn("No partitions assigned after rebalance wait{}",
                        properties.seekToBeginningOnStart() ? "; seekToBeginning skipped" : "");
            } else {
                if (properties.seekToBeginningOnStart()) {
                    createdConsumer.seekToBeginning(createdConsumer.assignment());
                }
                // position() resolves the reset offset now, so nothing sent after this point is skipped
                createdConsumer.assignment().forEach(createdConsumer::position);
            }
            assigned.countDown();

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
            buffer.collectorFailed(e.toString());
        } catch (RuntimeException e) {
            log.error("Kafka probe collector stopped unexpectedly", e);
            buffer.collectorFailed(e.toString());
        } finally {
            consumer = null;
            running.set(false);
            assigned.countDown();
        }
    }

    /** A group of its own per run unless told otherwise: shared groups split partitions between runs. */
    private Consumer<String, String> createConsumer() {
        if (!properties.uniqueGroupId()) {
            return consumerFactory.createConsumer();
        }
        Object configured;
        try {
            configured = consumerFactory.getConfigurationProperties()
                    .get(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG);
        } catch (UnsupportedOperationException e) {
            // a custom factory that does not expose its configuration
            configured = null;
        }
        String base = configured == null || configured.toString().isBlank() ? "testforge-probe" : configured.toString();
        String group = base + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        log.info("Kafka probe joins consumer group {}", group);
        return consumerFactory.createConsumer(group, null, null);
    }
}
