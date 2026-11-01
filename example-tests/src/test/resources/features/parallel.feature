Feature: Scenarios running at once stay apart
  The suite runs scenarios in parallel. Each copy below stubs the same path
  on the same WireMock with a different answer, and writes to the same
  table: each sees only its own stub, its own row and its own order.

  Scenario Outline: The same paths, four scenarios at once — copy <n>
    Given I remember a unique value as "orderId"
    And the mock answers GET "/stock/book" with 200:
      """
      {"item": "book", "available": <n>}
      """
    And I watch table "bdd_reservations" keyed by "order_id" where "order_id" is "${orderId}"
    And the order service accepts new orders
    When I send a GET request to "/stock/book"
    Then the response field "$.available" is "<n>"
    When the warehouse reserves <n> "book" for order "${orderId}"
    Then table "bdd_reservations" has 1 added, 0 changed and 0 removed rows
    And the mock received 1 GET request to "/stock/book"
    When I send a POST request to "/orders" with body:
      """
      {"item": "book"}
      """
    Then the response status is 201

    Examples:
      | n |
      | 1 |
      | 2 |
      | 3 |
      | 4 |
