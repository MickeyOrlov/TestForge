Feature: A scenario that fails after Kafka traffic (ScenarioDiagnosticsTest only)

  Scenario: Paying is rejected
    When I publish a Kafka message to "orders.commands" with key "o-1":
      """
      {"type": "PAY", "card": "4111"}
      """
    And the payment service answers for this scenario and for another one
    Then a Kafka message arrives on "orders.events" where "$.status" is "PAID"
