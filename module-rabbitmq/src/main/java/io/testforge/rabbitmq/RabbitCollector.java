package io.testforge.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.context.SmartLifecycle;

/**
 * Observes the configured exchanges without taking anything from the
 * system's own consumers: it declares a server-named, exclusive, auto-deleted
 * queue of its own, binds it to each {@code forge.rabbitmq.bindings} entry
 * and reads it. RabbitMQ copies every routed message to each bound queue, so
 * the services' queues receive exactly what they did before; the queue goes
 * away with the connection.
 *
 * <p>The bindings exist before {@link #start()} returns, so a message the
 * first test triggers is not missed. A binding that fails (an exchange that
 * does not exist) or a broker that cancels the consumer stops the collector
 * and records why in the buffer, where a timeout reports it.
 */
public class RabbitCollector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RabbitCollector.class);
    static final String CONSUMER_TAG = "testforge-probe";

    private final ConnectionFactory connectionFactory;
    private final RabbitProbeProperties properties;
    private final RabbitMessageBuffer buffer;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong deliveries = new AtomicLong();
    private volatile Channel channel;
    private volatile String queue;

    public RabbitCollector(ConnectionFactory connectionFactory, RabbitProbeProperties properties,
                           RabbitMessageBuffer buffer) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
        this.buffer = buffer;
    }

    @Override
    public void start() {
        if (properties.bindings().isEmpty()) {
            log.warn("RabbitMQ probe is enabled but forge.rabbitmq.bindings is empty; collector will not start");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        RabbitProbeProperties.Binding current = null;
        try {
            Channel opened = connectionFactory.createConnection().createChannel(false);
            channel = opened;
            queue = opened.queueDeclare("", false, true, true, null).getQueue();
            for (RabbitProbeProperties.Binding binding : properties.bindings()) {
                current = binding;
                opened.queueBind(queue, binding.exchange(), binding.routingKey());
            }
            opened.basicConsume(queue, true, CONSUMER_TAG, new Consumer(opened));
            log.info("RabbitMQ probe reads {} bound to {}", queue, properties.bindings());
        } catch (IOException | RuntimeException e) {
            String reason = current == null ? e.toString()
                    : "binding " + current.exchange() + " / " + current.routingKey() + " failed: " + cause(e);
            log.error("RabbitMQ probe collector did not start: {}", reason, e);
            buffer.collectorFailed(reason);
            close();
        }
    }

    /** The collector's own queue, once started: what the broker's management UI shows for it. */
    public String queue() {
        return queue;
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void close() {
        running.set(false);
        Channel opened = channel;
        channel = null;
        if (opened != null && opened.isOpen()) {
            try {
                // cancelling the only consumer deletes the auto-delete queue now, not when the connection closes
                opened.basicCancel(CONSUMER_TAG);
            } catch (IOException | RuntimeException e) {
                log.debug("RabbitMQ probe consumer did not cancel cleanly", e);
            }
            closePhysically(opened);
        }
    }

    /**
     * Spring's CachingConnectionFactory returns a closed channel to its cache;
     * a probe channel — with a consumer, or in confirm mode — must really close.
     */
    static void closePhysically(Channel channel) {
        try {
            RabbitUtils.setPhysicalCloseRequired(channel, true);
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException | java.util.concurrent.TimeoutException | RuntimeException e) {
            log.debug("RabbitMQ probe channel did not close cleanly", e);
        } finally {
            RabbitUtils.clearPhysicalCloseRequired();
        }
    }

    private static String cause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root instanceof ShutdownSignalException signal ? String.valueOf(signal.getReason()) : root.toString();
    }

    RabbitMessage received(Envelope envelope, AMQP.BasicProperties props, byte[] body) {
        Charset charset = StandardCharsets.UTF_8;
        if (props != null && props.getContentEncoding() != null && Charset.isSupported(props.getContentEncoding())) {
            charset = Charset.forName(props.getContentEncoding());
        }
        Instant timestamp = props != null && props.getTimestamp() != null
                ? props.getTimestamp().toInstant() : Instant.now();
        return new RabbitMessage(envelope.getExchange(), envelope.getRoutingKey(),
                body == null ? "" : new String(body, charset), timestamp,
                headers(props == null ? null : props.getHeaders()),
                props == null ? null : props.getMessageId(), deliveries.incrementAndGet());
    }

    /** AMQP header values as text: long strings decoded, everything else by its string form. */
    static Map<String, String> headers(Map<String, Object> raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((name, value) -> headers.put(name, value == null ? null
                    : value instanceof LongString text ? text.toString()
                    : value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(value)));
        }
        return headers;
    }

    private final class Consumer extends DefaultConsumer {

        Consumer(Channel channel) {
            super(channel);
        }

        @Override
        public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties props, byte[] body) {
            buffer.append(received(envelope, props, body));
        }

        @Override
        public void handleCancel(String tag) {
            if (running.get()) {
                buffer.collectorFailed("the broker cancelled the probe's consumer on " + queue
                        + " (queue deleted?)");
                running.set(false);
            }
        }

        @Override
        public void handleShutdownSignal(String tag, ShutdownSignalException signal) {
            if (running.get() && !signal.isInitiatedByApplication()) {
                log.error("RabbitMQ probe collector stopped: {}", signal.getMessage());
                buffer.collectorFailed(signal.getMessage());
                running.set(false);
            }
        }
    }
}
