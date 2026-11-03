package io.testforge.example;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.testforge.faults.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real TCP faults on owned containers only; no Docker in the default build. */
@Tag("containers")
class FaultsToxiproxyIT {
    private Faults controller(Network network, String upstream) {
        var properties = new FaultsProperties(true, null, null, network.getId(), false, 0, 0,
                Map.of("dependency", upstream), List.of(upstream));
        return new Faults(properties, new ToxiproxyBackend(properties), null);
    }
    @Test void postgresServiceFailsWithinItsTimeoutAndAnotherScopeIsUnaffected() throws Exception {
        try (Network network = Network.newNetwork();
             var db = new PostgreSQLContainer("postgres:17.4-alpine").withNetwork(network).withNetworkAliases("db")) {
            db.start();
            try (var faults = controller(network, "db:5432"); var slow = faults.on("dependency");
                 var healthy = faults.on("dependency")) {
                slow.latency(Duration.ofSeconds(2));
                long started = System.nanoTime();
                assertThat(service(slow.endpoint().address(), db.getUsername(), db.getPassword())).isEqualTo("DEPENDENCY_UNAVAILABLE");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(700), Duration.ofSeconds(3));
                assertThat(service(healthy.endpoint().address(), db.getUsername(), db.getPassword())).isEqualTo("OK");
                slow.close();
                try (var recovered = faults.on("dependency")) {
                    assertThat(service(recovered.endpoint().address(), db.getUsername(), db.getPassword())).isEqualTo("OK");
                }
            }
        }
    }
    private String service(String address, String user, String password) {
        try (var connection = DriverManager.getConnection("jdbc:postgresql://" + address
                + "/test?connectTimeout=1&socketTimeout=1&loginTimeout=2", user, password);
             var query = connection.createStatement(); var result = query.executeQuery("select 1")) {
            return result.next() ? "OK" : "EMPTY";
        } catch (java.sql.SQLException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof java.net.SocketTimeoutException) return "DEPENDENCY_UNAVAILABLE";
            return e.getSQLState() != null && e.getSQLState().startsWith("08") ? "DEPENDENCY_UNAVAILABLE" : "DEPENDENCY_ERROR";
        }
    }
    @Test void kafkaConsumerResumesAfterConnectionResetIsRemoved() throws Exception {
        try (Network network = Network.newNetwork(); var faults = controller(network, "kafka:19092");
             var scope = faults.on("dependency");
             var kafka = new KafkaContainer("apache/kafka:3.8.0").withNetwork(network).withNetworkAliases("kafka")
                     .withListener("kafka:19092", () -> scope.endpoint().address())) {
            kafka.start();
            String topic = "faults-" + UUID.randomUUID();
            Map<String, Object> producerConfig = new HashMap<>();
            producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            Map<String, Object> consumerConfig = new HashMap<>();
            consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, scope.endpoint().address());
            consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, topic);
            consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (var producer = new KafkaProducer<String, String>(producerConfig);
                 var consumer = new KafkaConsumer<String, String>(consumerConfig)) {
                producer.send(new ProducerRecord<>(topic, "before")).get(15, java.util.concurrent.TimeUnit.SECONDS);
                consumer.subscribe(List.of(topic));
                await().pollInSameThread().atMost(Duration.ofSeconds(30)).until(() -> consumer.poll(Duration.ofMillis(200)).count() > 0);
                scope.resetPeer();
                producer.send(new ProducerRecord<>(topic, "after")).get(15, java.util.concurrent.TimeUnit.SECONDS);
                consumer.poll(Duration.ofMillis(500)); // exercise the interrupted connection
                // Remove only this scope's reset; keep its proxy endpoint so the same consumer reconnects.
                scope.restore();
                await().pollInSameThread().atMost(Duration.ofSeconds(30)).until(() -> {
                    for (var record : consumer.poll(Duration.ofMillis(200))) if (record.value().equals("after")) return true;
                    return false;
                });
            }
        }
    }

    @Test void anExistingControllerKeepsForeignProxiesAndDeletesOnlyThisJvmScopes() throws Exception {
        try (var server = new org.testcontainers.toxiproxy.ToxiproxyContainer(ToxiproxyBackend.IMAGE)) {
            server.start();
            var client = new eu.rekawek.toxiproxy.ToxiproxyClient(server.getHost(), server.getControlPort());
            client.createProxy("foreign", "0.0.0.0:8697", "db:5432");
            var properties = new FaultsProperties(true, "http://" + server.getHost() + ":" + server.getControlPort(),
                    null, null, true, 0, 0, Map.of("db", "db:5432"), List.of("db:5432"));
            try (var faults = new Faults(properties, new ToxiproxyBackend(properties), null)) {
                try (var scope = faults.on("db")) {
                    scope.latency(Duration.ofMillis(5));
                    assertThat(client.getProxies()).hasSize(2);
                }
                assertThat(client.getProxies()).extracting(eu.rekawek.toxiproxy.Proxy::getName).containsExactly("foreign");
            }
            assertThat(client.getProxies()).extracting(eu.rekawek.toxiproxy.Proxy::getName).containsExactly("foreign");
        }
    }
}
