package io.testforge.gherkin.db;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.testforge.core.wait.Waiter;
import io.testforge.db.state.DbState;
import io.testforge.db.state.TableSnapshot;
import io.testforge.gherkin.GherkinProperties;
import io.testforge.gherkin.ScenarioVariables;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Database steps over {@link DbState}: a row the system wrote, a row it must
 * not write, and what an action changed in a table.
 *
 * <pre>
 * Given I watch table "orders" keyed by "id" where "customer_id" is "${customerId}"
 * When I send a POST request to "/orders/${orderId}/pay"
 * Then in table "orders" the row where "id" is "${orderId}" has "status" set to "PAID"
 * And table "orders" has 0 added, 1 changed and 0 removed rows
 * </pre>
 *
 * Values are compared with the column's text ({@code CAST(… AS VARCHAR)}), so
 * a step does not depend on the column type; a decimal reads as the database
 * prints it. Existence waits like any TestForge wait; absence watches for
 * {@code forge.gherkin.settle}. Names must be plain identifiers.
 */
public class DbSteps {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final DbState db;
    private final Waiter waiter;
    private final ScenarioVariables variables;
    private final GherkinProperties properties;
    private final Map<String, TableSnapshot> watched = new HashMap<>();

    public DbSteps(DbState db, Waiter waiter, ScenarioVariables variables, GherkinProperties properties) {
        this.db = db;
        this.waiter = waiter;
        this.variables = variables;
        this.properties = properties;
    }

    @Then("table {string} has a row where {string} is {string}")
    public void hasRow(String table, String column, String value) {
        awaitRows(table, condition(column), variables.resolve(value));
    }

    @Then("table {string} has a row where {string} is {string} and {string} is {string}")
    public void hasRow(String table, String column, String value, String other, String otherValue) {
        awaitRows(table, condition(column) + " AND " + condition(other), variables.resolve(value),
                variables.resolve(otherValue));
    }

    @Then("in table {string} the row where {string} is {string} has {string} set to {string}")
    public void rowHas(String table, String key, String keyValue, String column, String value) {
        hasRow(table, key, keyValue, column, value);
    }

    @Then("table {string} has no row where {string} is {string}")
    public void hasNoRow(String table, String column, String value) {
        String resolved = variables.resolve(value);
        String where = condition(column);
        try {
            Awaitility.await("no row of " + table + " where " + column + " is " + resolved)
                    .during(properties.settle()).atMost(properties.settle().plusSeconds(1))
                    .pollInterval(java.time.Duration.ofMillis(100)).pollInSameThread()
                    .until(() -> rows(table, where, resolved) == 0);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("table " + table + " has a row where " + column + " is " + resolved
                    + ", expected none within " + properties.settle(), e);
        }
    }

    @Given("I watch table {string} keyed by {string} where {string} is {string}")
    public void watch(String table, String key, String column, String value) {
        watched.put(name(table), db.table(name(table)).keyedBy(name(key))
                .where(condition(column), variables.resolve(value)).take());
    }

    /** What changed since the table was watched, with the diff in the failure. */
    @Then("table {string} has {int} added, {int} changed and {int} removed rows")
    public void changed(String table, int added, int changed, int removed) {
        snapshot(table).diff().expect().added(added).changed(changed).removed(removed);
    }

    @Then("table {string} is unchanged")
    public void unchanged(String table) {
        snapshot(table).assertUnchanged();
    }

    private TableSnapshot snapshot(String table) {
        TableSnapshot snapshot = watched.get(name(table));
        if (snapshot == null) {
            throw new IllegalStateException("Table " + table + " is not watched in this scenario: start with "
                    + "'I watch table \"" + table + "\" keyed by \"<key>\" where \"<column>\" is \"<value>\"'");
        }
        return snapshot;
    }

    private void awaitRows(String table, String where, Object... values) {
        try {
            waiter.await("a row of " + table + " where " + where, () -> rows(table, where, values), count -> count > 0);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("table " + table + " has no row where " + where + " with "
                    + java.util.Arrays.toString(values) + " (" + e.getMessage().lines().findFirst().orElse("") + ")", e);
        }
    }

    private int rows(String table, String where, Object... values) {
        return db.table(name(table)).keyedBy(firstColumn(where)).where(where, values).take().size();
    }

    private static String condition(String column) {
        return "CAST(" + name(column) + " AS VARCHAR) = ?";
    }

    /** The snapshot needs a key; any column of the condition serves, the count is all that is read. */
    private static String firstColumn(String where) {
        return where.substring("CAST(".length(), where.indexOf(" AS VARCHAR)"));
    }

    private static String name(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("'" + identifier + "' is not a plain table or column name");
        }
        return identifier;
    }
}
