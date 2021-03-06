package brooklyn.util.internal

import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*
import groovy.time.TimeDuration

import java.util.concurrent.TimeUnit

import org.testng.annotations.Test

import com.google.common.base.Stopwatch

public class RepeaterTest {
    static { TimeExtras.init() }

    @Test
    public void sanityTest() {
        new Repeater("Sanity test")
            .repeat()
            .until { true }
            .every(10 * MILLISECONDS);
    }

    @Test
    public void sanityTestDescription() {
        new Repeater()
            .repeat()
            .until { true }
            .every(10 * MILLISECONDS);
    }

    @Test
    public void sanityTestBuilder() {
        Repeater.create("Sanity test")
            .repeat()
            .until { true }
            .every(10 * MILLISECONDS);
    }

    @Test
    public void sanityTestBuilderDescription() {
        Repeater.create()
            .repeat()
            .until { true }
            .every(10 * MILLISECONDS);
    }

    @Test(expectedExceptions = [ NullPointerException.class ])
    public void repeatFailsIfClosureIsNull() {
        new Repeater("repeatFailsIfClosureIsNull").repeat(null);
        fail "Expected exception was not thrown"
    }

    @Test
    public void repeatSucceedsIfClosureIsNonNull() {
        new Repeater("repeatSucceedsIfClosureIsNonNull").repeat { true };
    }

    @Test(expectedExceptions = [ NullPointerException.class ])
    public void untilFailsIfClosureIsNull() {
        new Repeater("untilFailsIfClosureIsNull").until(null);
        fail "Expected exception was not thrown"
    }

    @Test
    public void untilSucceedsIfClosureIsNonNull() {
        new Repeater("untilSucceedsIfClosureIsNonNull").until { true };
    }

    @Test(expectedExceptions = [ IllegalArgumentException.class ])
    public void everyFailsIfPeriodIsZero() {
        new Repeater("everyFailsIfPeriodIsZero").every(0 * MILLISECONDS);
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ IllegalArgumentException.class ])
    public void everyFailsIfPeriodIsNegative() {
        new Repeater("everyFailsIfPeriodIsNegative").every(-1 * MILLISECONDS);
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ NullPointerException.class ])
    public void everyFailsIfUnitsIsNull() {
        new Repeater("everyFailsIfUnitsIsNull").every(10, null);
        fail "Expected exception was not thrown"
    }

    @Test
    public void everySucceedsIfPeriodIsPositiveAndUnitsIsNonNull() {
        new Repeater("repeatSucceedsIfClosureIsNonNull").every(10 * MILLISECONDS);
    }

    @Test(expectedExceptions = [ IllegalArgumentException.class ])
    public void limitTimeToFailsIfPeriodIsZero() {
        new Repeater("limitTimeToFailsIfPeriodIsZero").limitTimeTo(0, TimeUnit.MILLISECONDS);
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ IllegalArgumentException.class ])
    public void limitTimeToFailsIfPeriodIsNegative() {
        new Repeater("limitTimeToFailsIfPeriodIsNegative").limitTimeTo(-1, TimeUnit.MILLISECONDS);
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ NullPointerException.class ])
    public void limitTimeToFailsIfUnitsIsNull() {
        new Repeater("limitTimeToFailsIfUnitsIsNull").limitTimeTo(10, null);
        fail "Expected exception was not thrown"
    }

    @Test
    public void limitTimeToSucceedsIfPeriodIsPositiveAndUnitsIsNonNull() {
        new Repeater("limitTimeToSucceedsIfClosureIsNonNull").limitTimeTo(10, TimeUnit.MILLISECONDS);
    }

    @Test
    public void everyAcceptsDuration() {
        new Repeater("everyAcceptsDuration").every(new TimeDuration(0, 0, 1, 0));
    }

    @Test
    public void everyAcceptsLong() {
        new Repeater("everyAcceptsLong").every(1000L);
    }

    @Test
    public void everyAcceptsTimeUnit() {
        new Repeater("everyAcceptsTimeUnit").every(1000000L, TimeUnit.MICROSECONDS);
    }

    @Test
    public void runReturnsTrueIfExitConditionIsTrue() {
        assertTrue new Repeater("runReturnsTrueIfExitConditionIsTrue")
            .repeat()
            .every(1 * MILLISECONDS)
            .until { true }
            .run();
    }

    @Test
    public void runRespectsMaximumIterationLimitAndReturnsFalseIfReached() {
        int iterations = 0;
        assertFalse new Repeater("runRespectsMaximumIterationLimitAndReturnsFalseIfReached")
            .repeat { iterations++ }
            .every(1 * MILLISECONDS)
            .until { false }
            .limitIterationsTo(5)
            .run();
        assertEquals 5, iterations;
    }

    /**
     * Check that the {@link Repeater} will stop after a time limit.
     *
     * The repeater is configured to run every 100ms and never stop until the limit is reached.
     * This is given as {@link Repeater#limitTimeTo(groovy.time.Duration)} and the execution time
     * is then checked to ensure it is between 100% and 400% of the specified value. Due to scheduling
     * delays and other factors in a non RTOS system it is expected that the repeater will take much
     * longer to exit occasionally.
     *
     * @see #runRespectsMaximumIterationLimitAndReturnsFalseIfReached()
     */
    @Test(groups="Integration")
    public void runRespectsTimeLimitAndReturnsFalseIfReached() {
        final long LIMIT = 2000l;
        Repeater repeater = new Repeater("runRespectsTimeLimitAndReturnsFalseIfReached")
            .repeat()
            .every(100 * MILLISECONDS)
            .until { false }
            .limitTimeTo(LIMIT, TimeUnit.MILLISECONDS);

        Stopwatch stopwatch = new Stopwatch().start();
        boolean result = repeater.run();
        stopwatch.stop();

        assertFalse result;

        long difference = stopwatch.elapsedMillis();
        assertTrue(difference >= LIMIT, "Difference was: " + difference);
        assertTrue(difference < 4 * LIMIT, "Difference was: " + difference);
    }

    @Test(expectedExceptions = [ IllegalStateException.class ])
    public void runFailsIfUntilWasNotSet() {
        new Repeater("runFailsIfUntilWasNotSet")
            .repeat()
            .every(10 * MILLISECONDS)
            .run();
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ IllegalStateException.class ])
    public void runFailsIfEveryWasNotSet() {
        new Repeater("runFailsIfEveryWasNotSet")
            .repeat()
            .until { true }
            .run();
        fail "Expected exception was not thrown"
    }

    @Test(expectedExceptions = [ UnsupportedOperationException.class ])
    public void testRethrowsException() {
        boolean result = new Repeater("throwRuntimeException")
            .repeat()
            .every(10 * MILLISECONDS)
            .until { throw new UnsupportedOperationException("fail") }
            .rethrowException()
            .limitIterationsTo(2)
            .run();
        fail "Expected exception was not thrown"
    }

    @Test
    public void testNoRethrowsException() {
        try {
	        boolean result = new Repeater("throwRuntimeException")
	            .repeat()
	            .every(10 * MILLISECONDS)
	            .until { throw new UnsupportedOperationException("fail") }
	            .limitIterationsTo(2)
	            .run();
	        assertFalse result
        } catch (RuntimeException re) {
            fail "Exception should not have been thrown: " + re.getMessage()
        }
    }
	
	public void testFlags() {
		int count=0;
		new Repeater(period: 5*MILLISECONDS, timeout: 100*MILLISECONDS).repeat({ count++ }).until({ count>100}).run();
		assertTrue count>10
		assertTrue count<30
	}
	
}
