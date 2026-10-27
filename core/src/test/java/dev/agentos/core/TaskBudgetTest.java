package dev.agentos.core;

import org.junit.Test;

import java.util.HashSet;

import static org.junit.Assert.*;

public final class TaskBudgetTest {
    @Test public void equalValuesCanBindReopenedConfigurationWithoutObjectIdentity() {
        TaskBudget first = new TaskBudget(30, 100, 600_000);
        TaskBudget reopened = new TaskBudget(30, 100, 600_000);
        assertNotSame(first, reopened);
        assertEquals(first, reopened);
        assertEquals(reopened, first);
        assertEquals(TaskBudget.DEFAULT, reopened);
        assertEquals(first.hashCode(), reopened.hashCode());
        HashSet<TaskBudget> budgets = new HashSet<>();
        budgets.add(first); budgets.add(reopened); budgets.add(TaskBudget.DEFAULT);
        assertEquals(1, budgets.size());
    }

    @Test public void everyBudgetDimensionParticipatesInEquality() {
        TaskBudget budget = new TaskBudget(30, 100, 600_000);
        assertNotEquals(budget, new TaskBudget(31, 100, 600_000));
        assertNotEquals(budget, new TaskBudget(30, 101, 600_000));
        assertNotEquals(budget, new TaskBudget(30, 100, 600_001));
        assertNotEquals(budget, null);
        assertNotEquals(budget, "30/100/600000");
    }

    @Test public void coreRetainsMillisecondPrecisionWithinItsOriginalBounds() {
        TaskBudget minimum = new TaskBudget(TaskBudget.MIN_MODEL_ROUNDS, TaskBudget.MIN_TOOL_CALLS, TaskBudget.MIN_ACTIVE_MILLIS);
        assertEquals(1, minimum.maxActiveMillis);
        assertEquals(75, new TaskBudget(1, 1, 75).maxActiveMillis);
        TaskBudget maximum = new TaskBudget(TaskBudget.MAX_MODEL_ROUNDS, TaskBudget.MAX_TOOL_CALLS, TaskBudget.MAX_ACTIVE_MILLIS);
        assertEquals(300, maximum.maxModelRounds);
        assertEquals(1000, maximum.maxToolCalls);
        assertEquals(3_600_000, maximum.maxActiveMillis);
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(301, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(1, 1001, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(1, 1, 3_600_001));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(Integer.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TaskBudget(1, 1, Long.MAX_VALUE));
    }
}
