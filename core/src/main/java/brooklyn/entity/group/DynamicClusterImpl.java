package brooklyn.entity.group;

import static com.google.common.base.Preconditions.checkNotNull;
import groovy.lang.Closure;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.AbstractGroupImpl;
import brooklyn.entity.basic.BasicGroupImpl;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.EntityFactoryForLocation;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.trait.Startable;
import brooklyn.location.Location;
import brooklyn.management.Task;
import brooklyn.policy.Policy;
import brooklyn.util.GroovyJavaMethods;
import brooklyn.util.MutableList;
import brooklyn.util.MutableMap;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * A cluster of entities that can dynamically increase or decrease the number of entities.
 */
public class DynamicClusterImpl extends AbstractGroupImpl implements DynamicCluster {
    private static final Logger logger = LoggerFactory.getLogger(DynamicClusterImpl.class);

    // Mutex for synchronizing during re-size operations
    private final Object mutex = new Object[0];
    
    private static final Function<Collection<Entity>, Entity> defaultRemovalStrategy = new Function<Collection<Entity>, Entity>() {
        public Entity apply(Collection<Entity> contenders) {
            // choose last (i.e. newest) entity that is stoppable
            Entity result = null;
            for (Entity it : contenders) {
                if (it instanceof Startable) result = it;
            }
            return result;
        }
    };
    
    public DynamicClusterImpl() {
        super();
        setAttribute(SERVICE_UP, false);
    }
    
    /**
     * Instantiate a new DynamicCluster.
     * 
     * Valid properties are:
     * <ul>
     * <li>memberSpec - an {@EntitySpec) that defines entities to be created,
     * where the entities typically implementing {@link Startable}
     * <li>factory - an {@EntityFactory) (or {@link Closure}) that creates an {@link Entity},
     * typically implementing {@link Startable}, taking the {@link Map}
     * of properties from this cluster as an argument. This property is mandatory.
     * <li>initialSize - an {@link Integer} that is the number of nodes to start when the cluster's {@link #start(List)} method is
     * called. This property is optional, with a default of 1.
     * </ul>
     *
     * @param properties The properties of the cluster (those corresponding to config keys will be 
     *                   visible to created children by inheritance, but to set properties on children 
     *                   explicitly, use the memberSpec or factory)
     * @param parent the entity that owns this cluster (optional)
     */
    public DynamicClusterImpl(Map<?,?> properties, Entity parent) {
        super(properties, parent);
        setAttribute(SERVICE_UP, false);
    }
    public DynamicClusterImpl(Entity parent) {
        this(Maps.newLinkedHashMap(), parent);
    }
    public DynamicClusterImpl(Map<?,?> properties) {
        this(properties, null);
    }
    
    @Override
    public void setRemovalStrategy(Function<Collection<Entity>, Entity> val) {
        setConfig(REMOVAL_STRATEGY, checkNotNull(val, "removalStrategy"));
    }
    
    @Override
    public void setRemovalStrategy(Closure val) {
        setRemovalStrategy(GroovyJavaMethods.functionFromClosure(val));
    }

    protected Function<Collection<Entity>, Entity> getRemovalStrategy() {
        Function<Collection<Entity>, Entity> result = getConfig(REMOVAL_STRATEGY);
        return (result != null) ? result : defaultRemovalStrategy;
    }
    
    protected EntitySpec<?> getMemberSpec() {
        return getConfig(MEMBER_SPEC);
    }
    
    protected EntityFactory<?> getFactory() {
        return getConfig(FACTORY);
    }
    
    @Override
    public void setMemberSpec(EntitySpec<?> memberSpec) {
        setConfigEvenIfOwned(MEMBER_SPEC, memberSpec);
    }
    
    @Override
    public void setFactory(EntityFactory<?> factory) {
        setConfigEvenIfOwned(FACTORY, factory);
    }
    
    private Location getLocation() {
        return Iterables.getOnlyElement(getLocations());
    }
    
    private boolean isQuarantineEnabled() {
        return getConfig(QUARANTINE_FAILED_ENTITIES);
    }
    
    private Group getQuarantineGroup() {
        return getAttribute(QUARANTINE_GROUP);
    }
    
    @Override
    public void start(Collection<? extends Location> locs) {
        if (isQuarantineEnabled()) {
            Group quarantineGroup = new BasicGroupImpl(MutableMap.of("displayName", "quarantine"), this);
            Entities.manage(quarantineGroup);
            setAttribute(QUARANTINE_GROUP, quarantineGroup);
        }
        
        if (locs==null) throw new IllegalStateException("Null location supplied to start "+this);
        if (locs.size()!=1) throw new IllegalStateException("Wrong number of locations supplied to start "+this+": "+locs);
        addLocations(locs);
        setAttribute(SERVICE_STATE, Lifecycle.STARTING);
        Integer initialSize = getConfig(INITIAL_SIZE);
        resize(initialSize);
        if (getCurrentSize() != initialSize) {
            throw new IllegalStateException("On start of cluster "+this+", failed to get to initial size of "+initialSize+"; size is "+getCurrentSize());
        }
        for (Policy it : getPolicies()) { it.resume(); }
        setAttribute(SERVICE_STATE, Lifecycle.RUNNING);
        setAttribute(SERVICE_UP, calculateServiceUp());
    }

    @Override
    public void stop() {
        setAttribute(SERVICE_STATE, Lifecycle.STOPPING);
        setAttribute(SERVICE_UP, calculateServiceUp());
        for (Policy it : getPolicies()) { it.suspend(); }
        resize(0);
        setAttribute(SERVICE_STATE, Lifecycle.STOPPED);
        setAttribute(SERVICE_UP, calculateServiceUp());
    }

    @Override
    public void restart() {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public Integer resize(Integer desiredSize) {
        synchronized (mutex) {
            int currentSize = getCurrentSize();
            int delta = desiredSize - currentSize;
            if (delta != 0) {
                logger.info("Resize {} from {} to {}", new Object[] {this, currentSize, desiredSize});
            } else {
                if (logger.isDebugEnabled()) logger.debug("Resize no-op {} from {} to {}", new Object[] {this, currentSize, desiredSize});
            }
    
            if (delta > 0) {
                grow(delta);
            } else if (delta < 0) {
                shrink(delta);
            }
        }
        return getCurrentSize();
    }

    @Override
    public String replaceMember(String memberId) {
        Entity member = getEntityManager().getEntity(memberId);
        logger.info("In {}, replacing member {} ({})", new Object[] {this, memberId, member});

        if (member == null) {
            throw new NoSuchElementException("In "+this+", entity "+memberId+" cannot be resolved, so not replacing");
        }

        synchronized (mutex) {
            if (!getMembers().contains(member)) {
                throw new NoSuchElementException("In "+this+", entity "+member+" is not a member so not replacing");
            }
            
            Collection<Entity> addedEntities = grow(1);
            if (addedEntities.size() < 1) {
                String msg = String.format("In %s, failed to grow, to replace %s; not removing", this, member);
                throw new IllegalStateException(msg);
            }
            
            stopAndRemoveNode(member);
            
            return Iterables.get(addedEntities, 0).getId();
        }
    }

    /**
     * Increases the cluster size by the given number.
     */
    private Collection<Entity> grow(int delta) {
        Collection<Entity> addedEntities = Lists.newArrayList();
        for (int i = 0; i < delta; i++) {
            addedEntities.add(addNode());
        }
        Map<Entity, Task<?>> tasks = Maps.newLinkedHashMap();
        for (Entity entity: addedEntities) {
            Map<String,?> args = ImmutableMap.of("locations", ImmutableList.of(getLocation()));
            tasks.put(entity, entity.invoke(Startable.START, args));
        }
        Map<Entity, Throwable> errors = waitForTasksOnEntityStart(tasks);
        
        if (!errors.isEmpty()) {
            if (isQuarantineEnabled()) {
                quarantineFailedNodes(errors.keySet());
            } else {
                cleanupFailedNodes(errors.keySet());
            }
        }
        
        return MutableList.<Entity>builder().addAll(addedEntities).removeAll(errors.keySet()).build();
    }
    
    private void shrink(int delta) {
        Collection<Entity> removedEntities = Lists.newArrayList();
        
        for (int i = 0; i < (delta*-1); i++) { removedEntities.add(pickAndRemoveMember()); }

        // FIXME symmetry in order of added as child, managed, started, and added to group
        // FIXME assume stoppable; use logic of grow?
        Task<List<Void>> invoke = Entities.invokeEffectorList(this, removedEntities, Startable.STOP, Collections.<String,Object>emptyMap());
        try {
            invoke.get();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        } finally {
            for (Entity removedEntity : removedEntities) {
                discardNode(removedEntity);
            }
        }
    }
    
    private void quarantineFailedNodes(Collection<Entity> failedEntities) {
        for (Entity entity : failedEntities) {
            emit(ENTITY_QUARANTINED, entity);
            getQuarantineGroup().addMember(entity);
            removeMember(entity);
        }
    }
    
    private void cleanupFailedNodes(Collection<Entity> failedEntities) {
        // TODO Could also call stop on them?
        for (Entity entity : failedEntities) {
            discardNode(entity);
        }
    }
    
    /**
     * Default impl is to be up when running, and !up otherwise.
     */
    protected boolean calculateServiceUp() {
        return getAttribute(SERVICE_STATE) == Lifecycle.RUNNING;
    }
    
    protected Map<Entity, Throwable> waitForTasksOnEntityStart(Map<Entity,Task<?>> tasks) {
        // TODO Could have CompoundException, rather than propagating first
        Map<Entity, Throwable> errors = Maps.newLinkedHashMap();
        
        for (Map.Entry<Entity,Task<?>> entry : tasks.entrySet()) {
            Entity entity = entry.getKey();
            Task<?> task = entry.getValue();
            try {
                task.get();
            } catch (InterruptedException e) {
                throw Exceptions.propagate(e);
            } catch (Throwable t) {
                logger.error("Cluster "+this+" failed to start entity "+entity+" (removing): "+t, t);
                errors.put(entity, unwrapException(t));
            }
        }
        return errors;
    }
    
    protected Throwable unwrapException(Throwable e) {
        if (e instanceof ExecutionException) {
            return unwrapException(e.getCause());
        } else if (e instanceof org.codehaus.groovy.runtime.InvokerInvocationException) {
            return unwrapException(e.getCause());
        } else {
            return e;
        }
    }
    
    @Override
    public boolean removeChild(Entity child) {
        boolean changed = super.removeChild(child);
        if (changed) {
            removeMember(child);
        }
        return changed;
    }
    
    protected Map getCustomChildFlags() { return Maps.newLinkedHashMap(); }
    
    protected Entity addNode() {
        Map creation = Maps.newLinkedHashMap(getCustomChildFlags());
        if (logger.isDebugEnabled()) logger.debug("Creating and adding a node to cluster {}({}) with properties {}", new Object[] {this, getId(), creation});

        Entity entity = createNode(creation);
        Entities.manage(entity);
        addMember(entity);
        return entity;
    }

    protected Entity createNode(Map flags) {
        EntitySpec<?> memberSpec = getMemberSpec();
        if (memberSpec != null) {
            return addChild(EntitySpecs.wrapSpec(memberSpec).configure(flags));
        }
        
        EntityFactory<?> factory = getFactory();
        if (factory == null) { 
            throw new IllegalStateException("No member spec nor entity factory supplied for dynamic cluster "+this);
        }
        EntityFactory<?> factoryToUse = (factory instanceof EntityFactoryForLocation) ? ((EntityFactoryForLocation)factory).newFactoryForLocation(getLocation()) : factory;
        Entity entity = factoryToUse.newEntity(flags, this);
        if (entity==null) 
            throw new IllegalStateException("EntityFactory factory routine returned null entity, in "+this);
        
        return entity;
    }
    
    protected Entity pickAndRemoveMember() {
        
        // TODO use pluggable strategy; default is to remove newest
        // TODO inefficient impl
        Preconditions.checkState(getMembers().size() > 0, "Attempt to remove a node when members is empty, from cluster "+this);
        if (logger.isDebugEnabled()) logger.debug("Removing a node from {}", this);
        
        Entity entity = getRemovalStrategy().apply(getMembers());
        Preconditions.checkNotNull(entity, "No entity chosen for removal from "+getId());
        Preconditions.checkState(entity instanceof Startable, "Chosen entity for removal not stoppable: cluster="+this+"; choice="+entity);

        removeMember(entity);
        return entity;
    }
    
    protected void discardNode(Entity entity) {
        removeMember(entity);
        Entities.unmanage(entity);
    }
    
    protected void stopAndRemoveNode(Entity member) {
        removeMember(member);
        
        try {
            if (member instanceof Startable) {
                Task<?> task = member.invoke(Startable.STOP, Collections.<String,Object>emptyMap());
                try {
                    task.get();
                } catch (Exception e) {
                    throw Exceptions.propagate(e);
                }
            }
        } finally {
            Entities.unmanage(member);
        }
    }
}
