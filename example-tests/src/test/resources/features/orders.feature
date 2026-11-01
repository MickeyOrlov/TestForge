Feature: Orders over HTTP
  TestForge's HTTP steps over ApiClient, next to the project's own
  domain steps. Every scenario runs with a scope of its own, so the order
  service's stubs answer this scenario only.

  Scenario: An order is created and read back
    Given the order service accepts new orders
    When I send a POST request to "/orders" with body:
      """
      {"item": "book"}
      """
    Then the response status is 201
    And I remember the response field "$.id" as "orderId"
    When I send a GET request to "/orders/${orderId}"
    Then the response status is 200
    And the response field "$.id" is "${orderId}"
    And the response field "$.status" is "NEW"

  Scenario: Another scenario does not see the first one's order
    When I send a POST request to "/orders" with body:
      """
      {"item": "pen"}
      """
    Then the response status is 404
