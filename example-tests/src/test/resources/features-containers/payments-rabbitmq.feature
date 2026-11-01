@containers
Feature: Payments over RabbitMQ
  TestForge's RabbitMQ steps against a real broker: the command goes through
  the exchange, the collector watches the payments exchange on a queue of its
  own, and the payment service still gets every command.

  Scenario: Paying an order captures one payment
    Given I remember a unique value as "orderId"
    When I publish a RabbitMQ message to exchange "orders" with routing key "order.pay":
      """
      {"orderId": "${orderId}"}
      """
    Then exactly one RabbitMQ message arrives on exchange "payments" where "$.orderId" is "${orderId}"
    And I remember the RabbitMQ message field "$.status" as "status"
    And the remembered "status" is "CAPTURED"
