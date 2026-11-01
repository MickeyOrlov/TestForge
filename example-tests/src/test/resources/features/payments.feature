Feature: Payments over Kafka
  TestForge's Kafka steps: publish the command the way production does, then
  prove the service answered with exactly one event and nothing it must not send.

  Scenario: Paying an order emits one payment event
    Given I remember a unique value as "orderId"
    When I publish a Kafka message to "orders.commands" with key "${orderId}":
      """
      {"type": "PAY", "orderId": "${orderId}"}
      """
    Then exactly one Kafka message arrives on "orders.events" where "$.orderId" is "${orderId}"
    And I remember the Kafka message field "$.status" as "status"
    And the remembered "status" is "PAID"
    And no Kafka message arrives on "orders.events" where "$.refundOf" is "${orderId}"
