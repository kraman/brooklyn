package brooklyn.entity.group;

import groovy.lang.Closure;

import java.util.Collection;
import java.util.NoSuchElementException;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Effector;
import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.AbstractGroup;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Description;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.basic.MethodEffector;
import brooklyn.entity.basic.NamedParameter;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.event.basic.BasicNotificationSensor;
import brooklyn.util.flags.SetFromFlag;

import com.google.common.base.Function;

/**
 * A cluster of entities that can dynamically increase or decrease the number of entities.
 */
@ImplementedBy(DynamicClusterImpl.class)
public interface DynamicCluster extends AbstractGroup, Cluster {

    public static final Effector<String> REPLACE_MEMBER = new MethodEffector<String>(DynamicCluster.class, "replaceMember");

    @SetFromFlag("quarantineFailedEntities")
    public static final ConfigKey<Boolean> QUARANTINE_FAILED_ENTITIES = new BasicConfigKey<Boolean>(
            Boolean.class, "dynamiccluster.quarantineFailedEntities", "Whether to guarantine entities that fail to start, or to try to clean them up", false);

    public static final BasicAttributeSensor<Lifecycle> SERVICE_STATE = Attributes.SERVICE_STATE;

    public static final BasicNotificationSensor<Entity> ENTITY_QUARANTINED = new BasicNotificationSensor<Entity>(Entity.class, "dynamiccluster.entityQuarantined", "Entity failed to start, and has been quarantined");

    public static final AttributeSensor<Group> QUARANTINE_GROUP = new BasicAttributeSensor<Group>(Group.class, "dynamiccluster.quarantineGroup", "Group of quarantined entities that failed to start");
    
    @SetFromFlag("memberSpec")
    public static final ConfigKey<EntitySpec<?>> MEMBER_SPEC = new BasicConfigKey(
            EntitySpec.class, "dynamiccluster.memberspec", "entity spec for creating new cluster members", null);

    @SetFromFlag("factory")
    public static final ConfigKey<EntityFactory> FACTORY = new BasicConfigKey<EntityFactory>(
            EntityFactory.class, "dynamiccluster.factory", "factory for creating new cluster members", null);

    @SetFromFlag("removalStrategy")
    public static final ConfigKey<Function<Collection<Entity>, Entity>> REMOVAL_STRATEGY = new BasicConfigKey(
            Function.class, "dynamiccluster.removalstrategy", "strategy for deciding what to remove when down-sizing", null);
    
    /**
     * 
     * @param memberId
     * @throws NoSuchElementException If entity cannot be resolved, or it is not a member 
     */
    @Description("Replaces the entity with the given ID, if it is a member; first adds a new member, then removes this one. "+
            "Returns id of the new entity; or throws exception if couldn't be replaced.")
    public String replaceMember(@NamedParameter("memberId") @Description("The entity id of a member to be replaced") String memberId);
    
    public void setRemovalStrategy(Function<Collection<Entity>, Entity> val);
    
    public void setRemovalStrategy(Closure val);
    
    public void setMemberSpec(EntitySpec<?> memberSpec);
    
    public void setFactory(EntityFactory<?> factory);
}
