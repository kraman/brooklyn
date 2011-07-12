package brooklyn.entity.group

import static org.testng.AssertJUnit.*

import java.util.concurrent.atomic.AtomicInteger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.Test

import brooklyn.entity.Application
import brooklyn.entity.basic.AbstractApplication
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.trait.Resizable
import brooklyn.entity.trait.ResizeResult
import brooklyn.entity.trait.Startable
import brooklyn.location.Location
import brooklyn.location.basic.GeneralPurposeLocation
import brooklyn.management.Task

class DynamicClusterTest {
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void constructorRequiresThatNewEntityArgumentIsGiven() {
        new DynamicCluster(initialSize:1, new TestApplication())
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void constructorRequiresThatNewEntityArgumentIsAnEntity() {
        new DynamicCluster(initialSize:1,
            newEntity:new Startable() {
                void start(Collection<? extends Location> loc) { };
                void stop() { }
                void restart() { }
            },
            new TestApplication()
        )
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void constructorRequiresThatNewEntityArgumentIsStartable() {
        new DynamicCluster(initialSize:1, newEntity:new AbstractEntity() { }, new TestApplication())
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void constructorRequiresThatInitialSizeArgumentIsGiven() {
        new DynamicCluster(newEntity:{ new TestEntity() }, new TestApplication())
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void constructorRequiresThatInitialSizeArgumentIsAnInteger() {
        new DynamicCluster(newEntity:{ new TestEntity() }, initialSize: "foo", new TestApplication())
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void startMethodFailsIfLocationsParameterIsMissing() {
        DynamicCluster cluster = new DynamicCluster(newEntity:{ new TestEntity() }, initialSize:0, new TestApplication())
        cluster.start(null)
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void startMethodFailsIfLocationsParameterIsEmpty() {
        DynamicCluster cluster = new DynamicCluster(newEntity:{ new TestEntity() }, initialSize:0, new TestApplication())
        cluster.start([])
        fail "Did not throw expected exception"
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void startMethodFailsIfLocationsParameterHasMoreThanOneElement() {
        DynamicCluster cluster = new DynamicCluster(newEntity:{ new TestEntity() }, initialSize:0, new TestApplication())
        cluster.start([ new GeneralPurposeLocation(), new GeneralPurposeLocation() ])
        fail "Did not throw expected exception"
    }

    @Test
    public void resizeFromZeroToOneStartsANewEntityAndSetsItsOwner() {
        Collection<Location> locations = [new GeneralPurposeLocation()]
        TestEntity entity = new TestEntity()
        Application app = new TestApplication()
        DynamicCluster cluster = new DynamicCluster(newEntity:{ entity }, initialSize:0, app)

        cluster.start(locations)
        cluster.resize(1)
        assertEquals 1, entity.counter.get()
        assertEquals cluster, entity.owner
        assertEquals app, entity.application
    }

    @Test
    public void currentSizePropertyReflectsActualClusterSize() {
        Collection<Location> locations = [ new GeneralPurposeLocation() ]

        Application app = new AbstractApplication() { }
        DynamicCluster cluster = new DynamicCluster(newEntity:{ new TestEntity() }, initialSize:0, app)
        cluster.start(locations)

        assertEquals 0, cluster.currentSize

        ResizeResult rr = cluster.resize(1)
        assertEquals 1, rr.delta
        assertEquals 1, cluster.currentSize

        rr = cluster.resize(4)
        assertEquals 3, rr.delta
        assertEquals 4, cluster.currentSize
    }

    @Test
    public void resizeCanBeInvokedAsAnEffector() {
        Collection<Location> locations = [ new GeneralPurposeLocation() ]
        TestEntity entity = new TestEntity()
        Application app = new TestApplication()
        DynamicCluster cluster = new DynamicCluster(newEntity:{ entity }, initialSize:0, app)

        cluster.start(locations)
        Task<ResizeResult> task = cluster.invoke(Resizable.RESIZE, [ desiredSize: 1 ])
        
        assertNotNull task
        ResizeResult rr = task.get()
        assertNotNull rr
        assertEquals 1, rr.delta
        assertEquals 1, cluster.currentSize
    }

    @Test
    public void clusterSizeAfterStartIsInitialSize() {
        Collection<Location> locations = [ new GeneralPurposeLocation() ]
        Application app = new TestApplication()
        DynamicCluster cluster = new DynamicCluster(newEntity:{ new TestEntity() }, initialSize:2, app)
        cluster.start(locations)
        assertEquals 2, cluster.currentSize
    }

    @Test
    public void clusterLocationIsPassedOnToEntityStart() {
        Collection<Location> locations = [ new GeneralPurposeLocation() ]
        def entity = new TestEntity() {
            Collection<Location> stashedLocations = null
            @Override
            void start(Collection<? extends Location> loc) {
                super.start(loc)
                stashedLocations = loc
            }
        }
        Application app = new TestApplication()
        DynamicCluster cluster = new DynamicCluster(newEntity:{ entity }, initialSize:1, app)
        cluster.start(locations)

        assertNotNull entity.stashedLocations
        assertEquals 1, entity.stashedLocations.size()
        assertEquals locations[0], entity.stashedLocations[0]
    }

    @Test(enabled = false)
    public void resizeFromOneToZeroChangesClusterSize() {
        DynamicCluster cluster = new DynamicCluster(newEntity: {new TestEntity()}, initialSize: 1, new TestApplication())
        cluster.start([new GeneralPurposeLocation()])
        assertEquals 1, cluster.currentSize
        cluster.resize(0)
        assertEquals 0, cluster.currentSize
    }

    @Test(enabled = false)
    public void resizeFromOneToZeroStopsTheEntity() {
        TestEntity entity = new TestEntity()
        DynamicCluster cluster = new DynamicCluster(newEntity: {entity}, initialSize: 1, new TestApplication())
        cluster.start([new GeneralPurposeLocation()])
        assertEquals 1, entity.counter.get()
        cluster.resize(0)
        assertEquals 0, entity.counter.get()
    }

    @Test(enabled = false)
    public void stoppingTheClusterStopsTheEntity() {
        TestEntity entity = new TestEntity()
        DynamicCluster cluster = new DynamicCluster(newEntity: {entity}, initialSize: 1, new TestApplication())
        cluster.start([new GeneralPurposeLocation()])
        assertEquals 1, entity.counter.get()
        cluster.stop()
        assertEquals 0, entity.counter.get()
    }

    private static class TestApplication extends AbstractApplication {
        @Override String toString() { return "Application["+id[-8..-1]+"]" }
    }
 
    private static class TestEntity extends AbstractEntity implements Startable {
        private static final Logger logger = LoggerFactory.getLogger(DynamicCluster)
        AtomicInteger counter = new AtomicInteger(0)
        void start(Collection<? extends Location> loc) { logger.trace "Start"; counter.incrementAndGet() }
        void stop() { logger.trace "Stop"; counter.decrementAndGet() }
        void restart() { }
        @Override String toString() { return "Entity["+id[-8..-1]+"]" }
    }
}
