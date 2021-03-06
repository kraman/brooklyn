package brooklyn.entity.group;

import java.util.Map;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.AbstractGroup;
import brooklyn.entity.basic.EntityFactory;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.trait.Startable;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.util.flags.SetFromFlag;

import com.google.common.collect.ImmutableMap;

/**
 * When a dynamic fabric is started, it starts an entity in each of its locations. 
 * This entity will be the parent of each of the started entities. 
 */
@ImplementedBy(DynamicFabricImpl.class)
public interface DynamicFabric extends AbstractGroup, Startable, Fabric {

    public static final BasicAttributeSensor<Integer> FABRIC_SIZE = new BasicAttributeSensor<Integer>(Integer.class, "fabric.size", "Fabric size");
    
    @SetFromFlag("memberSpec")
    public static final ConfigKey<EntitySpec<?>> MEMBER_SPEC = new BasicConfigKey(
            EntitySpec.class, "dynamiccfabric.memberspec", "entity spec for creating new cluster members", null);

    @SetFromFlag("factory")
    public static final ConfigKey<EntityFactory> FACTORY = new BasicConfigKey<EntityFactory>(
            EntityFactory.class, "dynamicfabric.factory", "factory for creating new cluster members", null);

    @SetFromFlag("displayNamePrefix")
    public static final ConfigKey<String> DISPLAY_NAME_PREFIX = new BasicConfigKey<String>(
            String.class, "dynamicfabric.displayNamePrefix", "Display name prefix, for created children", null);

    @SetFromFlag("displayNameSuffix")
    public static final ConfigKey<String> DISPLAY_NAME_SUFFIX = new BasicConfigKey<String>(
            String.class, "dynamicfabric.displayNameSuffix", "Display name suffix, for created children", null);

    @SetFromFlag("customChildFlags")
    public static final ConfigKey<Map> CUSTOM_CHILD_FLAGS = new BasicConfigKey<Map>(
            Map.class, "dynamicfabric.customChildFlags", "Additional flags to be passed to children when they are being created", ImmutableMap.of());

    public void setMemberSpec(EntitySpec<?> memberSpec);
    
    public void setFactory(EntityFactory<?> factory);
    
    public Integer getFabricSize();
}
