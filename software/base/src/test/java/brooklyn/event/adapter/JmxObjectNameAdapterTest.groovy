package brooklyn.event.adapter

import static org.testng.Assert.assertEquals
import groovy.time.TimeDuration

import java.util.concurrent.TimeUnit

import javax.management.ObjectName

import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.basic.Attributes
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.location.basic.SimulatedLocation
import brooklyn.test.JmxService
import brooklyn.test.entity.TestApplication
import brooklyn.test.entity.TestApplicationImpl
import brooklyn.test.entity.TestEntity
import brooklyn.test.entity.TestEntityImpl

@Deprecated // Class under test is deprecated
public class JmxObjectNameAdapterTest {
    private static final long TIMEOUT = 5000

    public static final BasicAttributeSensor<Long> NONSENSE_SENSOR = [Long, "measures the amount of nonsense", "the nonsense"]

    TestApplication app
    TestEntity entity
    SensorRegistry registry
    JmxSensorAdapter jmxAdapter
    JmxHelper jmxHelper
    JmxService jmxService

    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        app = new TestApplicationImpl();
        entity = new TestEntityImpl(parent: app) {
            void start(Collection locs) {
                super.start(locs);
                entity.setAttribute(Attributes.HOSTNAME, "localhost");
                entity.setAttribute(Attributes.JMX_PORT, 40123)
                entity.setAttribute(Attributes.RMI_SERVER_PORT, 40124)
                entity.setAttribute(Attributes.JMX_CONTEXT)
            }
        };
        app.start([new SimulatedLocation()])

        registry = new SensorRegistry(entity);
        jmxAdapter = registry.register(new JmxSensorAdapter(period: 50 * TimeUnit.MILLISECONDS));
        jmxHelper = new JmxHelper(entity)

        jmxService = new JmxService(entity)
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (jmxHelper) jmxHelper.disconnect()
        if (jmxAdapter) registry.deactivateAdapters()
        if (jmxService) jmxService.shutdown()
    }


    @Test
    public void whenAttributeRegisteredWithPeriod() {
        jmxHelper.connect(TIMEOUT)
        JmxObjectNameAdapter objectNameAdapter = new JmxObjectNameAdapter(jmxAdapter, new ObjectName("somename", "somekey", "somevalue"));
        TimeDuration period = 20 * TimeUnit.SECONDS
        JmxAttributeAdapter attribute = objectNameAdapter.attribute(period: period, "somename")
        attribute.subscribe(NONSENSE_SENSOR)

        assertEquals(20, attribute.pollPeriod.getSeconds())
    }
}
