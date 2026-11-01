package io.testforge.example.gherkin;

import io.cucumber.java.Before;
import io.cucumber.java.en.When;
import io.testforge.gherkin.ScenarioVariables;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The warehouse, in miniature: reserving writes a row, the way the real
 * service would. In a project this step calls the product's API; the database
 * steps then check what it stored.
 */
public class WarehouseSteps {

    private final JdbcTemplate jdbc;
    private final ScenarioVariables variables;

    public WarehouseSteps(JdbcTemplate jdbc, ScenarioVariables variables) {
        this.jdbc = jdbc;
        this.variables = variables;
    }

    @Before
    public void table() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS bdd_reservations (order_id VARCHAR(64) PRIMARY KEY, "
                + "item VARCHAR(32), quantity INT)");
    }

    @When("the warehouse reserves {int} {string} for order {string}")
    public void reserve(int quantity, String item, String order) {
        jdbc.update("INSERT INTO bdd_reservations VALUES (?, ?, ?)", variables.resolve(order), item, quantity);
    }
}
