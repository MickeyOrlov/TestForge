Feature: Stock and reservations
  TestForge's mock and database steps: the stock service is a stub of this
  scenario's own scope, and the reservation is checked where the system
  stores it.

  Scenario: Reserving stock asks the stock service once and records one reservation
    Given I remember a unique value as "orderId"
    And the mock answers GET "/stock/book" with 200:
      """
      {"item": "book", "available": 3}
      """
    And I watch table "bdd_reservations" keyed by "order_id" where "order_id" is "${orderId}"
    When I send a GET request to "/stock/book"
    Then the response field "$.available" is "3"
    When the warehouse reserves 1 "book" for order "${orderId}"
    Then table "bdd_reservations" has 1 added, 0 changed and 0 removed rows
    And in table "bdd_reservations" the row where "order_id" is "${orderId}" has "quantity" set to "1"
    And table "bdd_reservations" has no row where "order_id" is "${orderId}-copy"
    And the mock received 1 GET request to "/stock/book"
    And the mock received no POST request to "/stock/book"
