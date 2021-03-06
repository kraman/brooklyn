package brooklyn.test.entity;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Description;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.basic.NamedParameter;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.location.Location;
import brooklyn.util.MutableMap;

/**
 * Mock entity for testing.
 */
public class TestEntityImpl extends AbstractEntity implements TestEntity {
	protected static final Logger LOG = LoggerFactory.getLogger(TestEntityImpl.class);

    int sequenceValue = 0;
    AtomicInteger counter = new AtomicInteger(0);
    Map constructorProperties;

    public TestEntityImpl() {
        super();
    }
    public TestEntityImpl(Map properties) {
        this(properties, null);
    }
    public TestEntityImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }
    public TestEntityImpl(Map properties, Entity parent) {
        super(properties, parent);
        this.constructorProperties = properties;
    }
    
    @Override
    public boolean isLegacyConstruction() {
        return super.isLegacyConstruction();
    }
    
    @Description("an example of a no-arg effector")
    public void myEffector() {
        if (LOG.isTraceEnabled()) LOG.trace("In myEffector for {}", this);
    }
    
    @Description("returns the arg passed in")
    public Object identityEffector(@NamedParameter("arg") @Description("val to return") Object arg) {
        if (LOG.isTraceEnabled()) LOG.trace("In identityEffector for {}", this);
        return arg;
    }
    
    @Override
    public AtomicInteger getCounter() {
        return counter;
    }
    
    @Override
    public int getCount() {
        return counter.get();
    }

    @Override
    public Map getConstructorProperties() {
        return constructorProperties;
    }
    
    @Override
    public synchronized int getSequenceValue() {
        return sequenceValue;
    }

    @Override
    public synchronized void setSequenceValue(int value) {
        sequenceValue = value;
        setAttribute(SEQUENCE, value);
    }

    @Override
    public void start(Collection<? extends Location> locs) {
        LOG.trace("Starting {}", this);
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        counter.incrementAndGet();
        // FIXME: Shouldn't need to clear() the locations, but for the dirty workaround implemented in DynamicFabric
        getLocations().clear(); ;
        getLocations().addAll(locs);
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
    }

    @Override
    public void stop() { 
        LOG.trace("Stopping {}", this);
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        counter.decrementAndGet();
        setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
    }

    @Override
    public void restart() {
        throw new UnsupportedOperationException();
    }
    
    /**
     * TODO Rename to addChild
     */
    @Override
    public <T extends Entity> T createChild(EntitySpec<T> spec) {
        return addChild(spec);
    }

    @Override
    public <T extends Entity> T createAndManageChild(EntitySpec<T> spec) {
        if (!getManagementSupport().isDeployed()) throw new IllegalStateException("Entity "+this+" not managed");
        T child = createChild(spec);
        getEntityManager().manage(child);
        return child;
    }
    
    @Override
    public String toString() {
        String id = getId();
        return "Entity["+id.substring(Math.max(0, id.length()-8))+"]";
    }
    
    // TODO add more mock methods
}
