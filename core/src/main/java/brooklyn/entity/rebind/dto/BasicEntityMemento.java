package brooklyn.entity.rebind.dto;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.EntityTypes;
import brooklyn.event.AttributeSensor;
import brooklyn.event.Sensor;
import brooklyn.mementos.EntityMemento;
import brooklyn.mementos.TreeNode;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Represents the state of an entity, so that it can be reconstructed (e.g. after restarting brooklyn).
 * 
 * @see AbstractEntity.getMemento()
 * @see AbstractEntity.rebind
 * 
 * @author aled
 */
@JsonAutoDetect(fieldVisibility=Visibility.ANY, getterVisibility=Visibility.NONE)
public class BasicEntityMemento extends AbstractTreeNodeMemento implements EntityMemento, Serializable {

    private static final long serialVersionUID = 8642959541121050126L;
    
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AbstractTreeNodeMemento.Builder<Builder> {
        protected boolean isTopLevelApp;
        protected Map<ConfigKey, Object> config = Maps.newLinkedHashMap();
        protected Map<AttributeSensor, Object> attributes = Maps.newLinkedHashMap();
        protected Set<ConfigKey> entityReferenceConfigs = Sets.newLinkedHashSet();
        protected Set<AttributeSensor> entityReferenceAttributes = Sets.newLinkedHashSet();
        protected Set<ConfigKey> locationReferenceConfigs = Sets.newLinkedHashSet();
        protected Set<AttributeSensor> locationReferenceAttributes = Sets.newLinkedHashSet();
        protected List<String> locations = Lists.newArrayList();
        protected List<String> policies = Lists.newArrayList();
        protected List<String> members = Lists.newArrayList();
        
        public Builder from(EntityMemento other) {
            super.from((TreeNode)other);
            isTopLevelApp = other.isTopLevelApp();
            displayName = other.getDisplayName();
            config.putAll(other.getConfig());
            attributes.putAll(other.getAttributes());
            entityReferenceConfigs.addAll(other.getEntityReferenceConfigs());
            entityReferenceAttributes.addAll(other.getEntityReferenceAttributes());
            locationReferenceConfigs.addAll(other.getLocationReferenceConfigs());
            locationReferenceAttributes.addAll(other.getLocationReferenceAttributes());
            locations.addAll(other.getLocations());
            policies.addAll(other.getPolicies());
            members.addAll(other.getMembers());
            return this;
        }
        public EntityMemento build() {
            invalidate();
            return new BasicEntityMemento(this);
        }
    }
    
    // TODO can this be inferred?
    private boolean isTopLevelApp;
    
    private Map<String, Object> config;
    private List<String> locations;
    private List<String> members;
    private Map<String, Object> attributes;
    private Set<String> entityReferenceConfigs;
    private Set<String> entityReferenceAttributes;
    private Set<String> locationReferenceConfigs;
    private Set<String> locationReferenceAttributes;
    private List<String> policies;
    
    // TODO can we move some of these to entity type, or remove/re-insert those which are final statics?
    private Map<String, ConfigKey> configKeys;
    private transient Map<String, ConfigKey<?>> staticConfigKeys;
    private Map<String, AttributeSensor> attributeKeys;
    private transient Map<String, Sensor<?>> staticSensorKeys;
    
    private transient Map<ConfigKey, Object> configByKey;
    private transient Map<AttributeSensor, Object> attributesByKey;
    private transient Set<ConfigKey> entityReferenceConfigsByKey;
    private transient Set<AttributeSensor> entityReferenceAttributesByKey;
    private transient Set<ConfigKey> locationReferenceConfigsByKey;
    private transient Set<AttributeSensor> locationReferenceAttributesByKey;

    // for de-serialization
    @SuppressWarnings("unused")
    private BasicEntityMemento() {
    }

    // Trusts the builder to not mess around with mutability after calling build() -- with invalidate pattern
    // Does not make any attempt to make unmodifiable, or immutable copy, to have cleaner (and faster) output
    protected BasicEntityMemento(Builder builder) {
        super(builder);
        isTopLevelApp = builder.isTopLevelApp;
        locations = toPersistedList(builder.locations);
        policies = toPersistedList(builder.policies);
        members = toPersistedList(builder.members);
        
        configByKey = builder.config;
        attributesByKey = builder.attributes;
        entityReferenceConfigsByKey = builder.entityReferenceConfigs;
        entityReferenceAttributesByKey = builder.entityReferenceAttributes;
        locationReferenceConfigsByKey = builder.locationReferenceConfigs;
        locationReferenceAttributesByKey = builder.locationReferenceAttributes;
        
        if (configByKey!=null) {
            configKeys = Maps.newLinkedHashMap();
            config = Maps.newLinkedHashMap();
            for (Map.Entry<ConfigKey, Object> entry : configByKey.entrySet()) {
                ConfigKey key = entry.getKey();
                if (!key.equals(getStaticConfigKeys().get(key.getName())))
                    configKeys.put(key.getName(), key);
                config.put(key.getName(), entry.getValue());
            }
            configKeys = toPersistedMap(configKeys);
            config = toPersistedMap(config);
        }
        
        if (attributesByKey!=null) {
            attributeKeys = Maps.newLinkedHashMap();
            attributes = Maps.newLinkedHashMap();
            for (Map.Entry<AttributeSensor, Object> entry : attributesByKey.entrySet()) {
                AttributeSensor key = entry.getKey();
                if (!key.equals(getStaticSensorKeys().get(key.getName())))
                    attributeKeys.put(key.getName(), key);
                attributes.put(key.getName(), entry.getValue());
            }
            attributeKeys = toPersistedMap(attributeKeys);
            attributes = toPersistedMap(attributes);
        }
        
        if (entityReferenceConfigsByKey!=null) {
            entityReferenceConfigs = Sets.newLinkedHashSet();
            for (ConfigKey key : entityReferenceConfigsByKey) {
                entityReferenceConfigs.add(key.getName());
            }
            entityReferenceConfigs = toPersistedSet(entityReferenceConfigs);
        }
        
        if (entityReferenceAttributesByKey!=null) {
            entityReferenceAttributes = Sets.newLinkedHashSet();
            for (AttributeSensor key : entityReferenceAttributesByKey) {
                entityReferenceAttributes.add(key.getName());
            }
            entityReferenceAttributes = toPersistedSet(entityReferenceAttributes);
        }

        if (locationReferenceConfigsByKey!=null) {
            locationReferenceConfigs = Sets.newLinkedHashSet();
            for (ConfigKey key : locationReferenceConfigsByKey) {
                locationReferenceConfigs.add(key.getName());
            }
            locationReferenceConfigs = toPersistedSet(locationReferenceConfigs);
        }
        
        if (locationReferenceAttributesByKey!=null) {
            locationReferenceAttributes = Sets.newLinkedHashSet();
            for (AttributeSensor key : locationReferenceAttributesByKey) {
                locationReferenceAttributes.add(key.getName());
            }
            locationReferenceAttributes = toPersistedSet(locationReferenceAttributes);
        }
    }

    protected synchronized Map<String, ConfigKey<?>> getStaticConfigKeys() {
        if (staticConfigKeys==null) 
            staticConfigKeys = EntityTypes.getDefinedConfigKeys(getType());
        return staticConfigKeys;
    }

    protected ConfigKey<?> getConfigKey(String key) {
        if (configKeys!=null) {
            ConfigKey<?> ck = configKeys.get(key);
            if (ck!=null) return ck;
        }
        return getStaticConfigKeys().get(key);
    }

    protected synchronized Map<String, Sensor<?>> getStaticSensorKeys() {
        if (staticSensorKeys==null) 
            staticSensorKeys = EntityTypes.getDefinedSensors(getType());
        return staticSensorKeys;
    }

    protected AttributeSensor<?> getAttributeKey(String key) {
        if (attributeKeys!=null) {
            AttributeSensor<?> ak = attributeKeys.get(key);
            if (ak!=null) return ak;
        }
        return (AttributeSensor<?>) getStaticSensorKeys().get(key);
    }

    /**
     * Creates the appropriate data-structures for the getters, from the serialized forms.
     * The serialized form is string->object (e.g. using attribute sensor name), whereas 
     * the getters return Map<AttributeSensor,Object> for example.
     * 
     * TODO Really don't like this pattern. Should we clean it up? But deferring until 
     * everything else is working.
     */
    private void postDeserialize() {
        configByKey = Maps.newLinkedHashMap();
        entityReferenceConfigsByKey = Sets.newLinkedHashSet();
        locationReferenceConfigsByKey = Sets.newLinkedHashSet();
        if (config!=null) {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                configByKey.put(getConfigKey(entry.getKey()), entry.getValue());
            }
        }
        if (entityReferenceConfigs!=null) {
            for (String key : entityReferenceConfigs) {
                entityReferenceConfigsByKey.add(getConfigKey(key));
            }
        }
        if (locationReferenceConfigs!=null) {
            for (String key : locationReferenceConfigs) {
                locationReferenceConfigsByKey.add(getConfigKey(key));
            }
        }

        attributesByKey = Maps.newLinkedHashMap();
        entityReferenceAttributesByKey = Sets.newLinkedHashSet();
        locationReferenceAttributesByKey = Sets.newLinkedHashSet();
        if (attributes!=null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                attributesByKey.put(getAttributeKey(entry.getKey()), entry.getValue());
            }
        }
        if (entityReferenceAttributes!=null) {
            for (String key : entityReferenceAttributes) {
                entityReferenceAttributesByKey.add(getAttributeKey(key));
            }
        }
        if (locationReferenceAttributes!=null) {
            for (String key : locationReferenceAttributes) {
                locationReferenceAttributesByKey.add(getAttributeKey(key));
            }
        }
    }
    
    @Override
    public boolean isTopLevelApp() {
        return isTopLevelApp;
    }

    @Override
    public Map<ConfigKey, Object> getConfig() {
        if (configByKey == null) postDeserialize();
        return Collections.unmodifiableMap(configByKey);
    }
    
    @Override
    public Map<AttributeSensor, Object> getAttributes() {
        if (attributesByKey == null) postDeserialize();
        return Collections.unmodifiableMap(attributesByKey);
    }
    
    @Override
    public Set<AttributeSensor> getEntityReferenceAttributes() {
        if (entityReferenceAttributesByKey == null) postDeserialize();
        return Collections.unmodifiableSet(entityReferenceAttributesByKey);
    }
    
    @Override
    public Set<ConfigKey> getEntityReferenceConfigs() {
        if (entityReferenceConfigsByKey == null) postDeserialize();
        return Collections.unmodifiableSet(entityReferenceConfigsByKey);
    }
    
    @Override
    public Set<AttributeSensor> getLocationReferenceAttributes() {
        if (locationReferenceAttributesByKey == null) postDeserialize();
        return Collections.unmodifiableSet(locationReferenceAttributesByKey);
    }
    
    @Override
    public Set<ConfigKey> getLocationReferenceConfigs() {
        if (locationReferenceConfigsByKey == null) postDeserialize();
        return Collections.unmodifiableSet(fromPersistedSet(locationReferenceConfigsByKey));
    }
    
    @Override
    public List<String> getPolicies() {
        return fromPersistedList(policies);
    }
    
    @Override
    public List<String> getMembers() {
        return fromPersistedList(members);
    }
    
    @Override
    public List<String> getLocations() {
        return fromPersistedList(locations);
    }

}
