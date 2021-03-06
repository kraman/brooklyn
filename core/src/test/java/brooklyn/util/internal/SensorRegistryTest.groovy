package brooklyn.util.internal

import static brooklyn.test.TestUtils.*
import static org.testng.Assert.*

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.Application
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.basic.Entities
import brooklyn.event.adapter.SensorRegistry
import brooklyn.event.adapter.legacy.ValueProvider
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.test.TestUtils
import brooklyn.test.entity.TestApplicationImpl
import brooklyn.test.entity.TestEntityImpl

/**
 * Test the operation of the {@link SensorRegistry} class.
 */
public class SensorRegistryTest {
    private static final Logger log = LoggerFactory.getLogger(SensorRegistryTest.class)

    Application app;
    AbstractEntity entity;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() {
        app = new TestApplicationImpl();
        entity = new TestEntityImpl(app)
        Entities.startManagement(app);
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (app != null) Entities.destroyAll(app)
    }
    
    @Test
    public void sensorUpdatedPeriodically() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        executeUntilSucceeds {
            assertEquals(entity.getAttribute(FOO), 1)
        }
        desiredVal.set(2)
        executeUntilSucceeds {
            assertEquals(entity.getAttribute(FOO), 2)
        }
    }
    
    @Test
    public void sensorUpdateDefaultPeriodIsUsed() {
        final int PERIOD = 250
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:PERIOD, connectDelay:0])
        
        List<Long> callTimes = [] as CopyOnWriteArrayList
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { callTimes.add(System.currentTimeMillis()); return 1 } as ValueProvider)
        
        Thread.sleep(500)
        assertApproxPeriod(callTimes, PERIOD, 500)
    }

    // takes 500ms, so marked as integration
    @Test(groups="Integration")
    public void sensorUpdatePeriodOverrideIsUsed() {
        final int PERIOD = 250
        // Create an entity and configure it with the above JMX service
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:1000, connectDelay:0])
        
        List<Long> callTimes = [] as CopyOnWriteArrayList
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { callTimes.add(System.currentTimeMillis()); return 1 } as ValueProvider, PERIOD)
        
        Thread.sleep(500)
        assertApproxPeriod(callTimes, PERIOD, 500)
    }

    private int removeSensorStopsItBeingUpdatedManyTimesCounter = 0;
    @Test(groups=["Integration","Acceptance"], invocationCount=100)
    public void testRemoveSensorStopsItBeingUpdatedManyTimes() {
        log.info("running testRemoveSensorStopsItBeingUpdated iteration {}", ++removeSensorStopsItBeingUpdatedManyTimesCounter);
        testRemoveSensorStopsItBeingUpdated();
    }
    
    @Test(groups="Integration")
    public void testRemoveSensorStopsItBeingUpdated() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        TimeExtras.init();
        TestUtils.executeUntilSucceeds(period:1*TimeUnit.MILLISECONDS, timeout:10*TimeUnit.SECONDS, { entity.getAttribute(FOO)!=null });
        assertEquals(entity.getAttribute(FOO), 1)
        
        sensorRegistry.removeSensor(FOO)
        
        // The poller could already be calling the value provider, so can't simply assert never called again.
        // And want to ensure that it is never called again (after any currently executing call), so need to wait.
        // TODO Nicer way than a sleep?  (see comment in TestUtils about need for blockUntilTrue)
        
        int nn = 1;
        TestUtils.executeUntilSucceeds(period:10*TimeUnit.MILLISECONDS, timeout:1*TimeUnit.SECONDS,
            {
                desiredVal.set(++nn);
                TestUtils.assertSucceedsContinually(period:10*TimeUnit.MILLISECONDS,
                    timeout:1000*TimeUnit.MILLISECONDS, {
                        entity.getAttribute(FOO)!=nn
                    });
            }
        );
    
        desiredVal.set(-1)
        Thread.sleep(100)
        assertNotEquals(entity.getAttribute(FOO), -1)
        
        sensorRegistry.updateAll()
        assertNotEquals(entity.getAttribute(FOO), -1)
        
        try {
            sensorRegistry.update(FOO)
            fail()
        } catch (IllegalStateException e) {
            // success
        }
    }

    @Test(groups="Integration")
    public void testClosePollerStopsItBeingUpdated() {
        SensorRegistry sensorRegistry = new SensorRegistry(entity, [period:50])
        
        final AtomicInteger desiredVal = new AtomicInteger(1)
        BasicAttributeSensor<Integer> FOO = [ Integer, "foo", "My foo" ]
        sensorRegistry.addSensor(FOO, { return desiredVal.get() } as ValueProvider)

        Thread.sleep(100)
        assertEquals(entity.getAttribute(FOO), 1)
        
        sensorRegistry.close()
        
        // The poller could already be calling the value provider, so can't simply assert never called again.
        // And want to ensure that it is never called again (after any currently executing call), so need to wait.
        // TODO Nicer way than a sleep?
        
        Thread.sleep(100)
        desiredVal.set(2)
        Thread.sleep(100)
        assertEquals(entity.getAttribute(FOO), 1)
    }

    private void assertApproxPeriod(List<Long> actual, int expectedInterval, long expectedDuration) {
        final long ACCEPTABLE_VARIANCE = 200
        final long ACCEPTABLE_DURATION_VARIANCE = 1000
        long minNextExpected = actual.get(0);
        actual.each {
            assertTrue it >= minNextExpected && it <= (minNextExpected+ACCEPTABLE_VARIANCE),
                    "expected=$minNextExpected, actual=$it, interval=$expectedInterval, series=$actual, duration=$expectedDuration"
            minNextExpected += expectedInterval
        }
        
        // Previously was stricter: Math.abs(actual.size()-expectedSize) <= 1
        // But that failed in jenkins once: actualSize=5, duration=500, interval=250, time between first and last 1011ms
        // Therefore be more relaxed (particularly because it's testing deprecated code)
        int expectedMinSize = expectedDuration/expectedInterval - 1;
        int expectedMaxSize = (expectedDuration+ACCEPTABLE_DURATION_VARIANCE)/expectedInterval + 1;
        int actualSize = actual.size();
        assertTrue(actualSize >= expectedMinSize && actualSize <= expectedMaxSize, "actualSize=$actualSize, series=$actual, duration=$expectedDuration, interval=$expectedInterval");
    }
    
}
