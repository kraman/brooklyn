package brooklyn.entity.osgi.karaf;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;

import org.osgi.jmx.JmxConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Description;
import brooklyn.entity.basic.NamedParameter;
import brooklyn.entity.basic.SoftwareProcessImpl;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.adapter.JmxHelper;
import brooklyn.event.adapter.JmxObjectNameAdapter;
import brooklyn.event.adapter.JmxPostProcessors;
import brooklyn.event.adapter.JmxSensorAdapter;
import brooklyn.event.feed.ConfigToAttributes;
import brooklyn.util.MutableMap;
import brooklyn.util.ResourceUtils;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.internal.LanguageUtils;

import com.google.common.base.Function;
import com.google.common.collect.Maps;

/**
 * This sets up a Karaf OSGi container
 */
public class KarafContainerImpl extends SoftwareProcessImpl implements KarafContainer {
    
    // TODO Better way of setting/overriding defaults for config keys that are defined in super class SoftwareProcess

    protected static final Logger LOG = LoggerFactory.getLogger(KarafContainerImpl.class);

    public static final String KARAF_ADMIN = "org.apache.karaf:type=admin,name=%s";
    public static final String KARAF_FEATURES = "org.apache.karaf:type=features,name=%s";

    public static final String OSGI_BUNDLE_STATE = "osgi.core:type=bundleState,version=1.5";
    public static final String OSGI_FRAMEWORK = "osgi.core:type=framework,version=1.5";
    public static final String OSGI_COMPENDIUM = "osgi.compendium:service=cm,version=1.3";

    protected JmxSensorAdapter jmxAdapter;
    protected JmxHelper jmxHelper;
    
    public KarafContainerImpl() {
        super();
    }

    public KarafContainerImpl(Map properties) {
        super(properties, null);
    }

    public KarafContainerImpl(Entity parent) {
        super(Maps.newLinkedHashMap(), parent);
    }

    public KarafContainerImpl(Map properties, Entity parent) {
        super(properties, parent);
    }

    @Override
    public Class<KarafDriver> getDriverInterface() {
        return KarafDriver.class;
    }

    @Override
    public KarafDriver getDriver() {
        return (KarafDriver) super.getDriver();
    }
    
    @Override
    protected void connectSensors() {
        super.connectSensors();

        //FIXME should have a better way of setting config -- firstly, not here!
        //preferred style is to have config auto-applied to attributes, and have default values in their definition, not here
        //use of "properties.{user,password}" is non-standard; is that requried? use default jmxUser, jmxPassword flags?
        setAttribute(JMX_CONTEXT, String.format("karaf-%s", getConfig(KARAF_NAME.getConfigKey())));
        
        ConfigToAttributes.apply(this);

        jmxAdapter = sensorRegistry.register(new JmxSensorAdapter(MutableMap.of("period", 500)));

        JmxObjectNameAdapter karafAdminObjectNameAdapter = jmxAdapter.objectName(String.format(KARAF_ADMIN, getConfig(KARAF_NAME.getConfigKey())));
        karafAdminObjectNameAdapter.attribute("Instances").subscribe(KARAF_INSTANCES, JmxPostProcessors.tabularDataToMap());
        
        // If MBean is unreachable, then mark as service-down
        karafAdminObjectNameAdapter.reachable().poll(new Function<Boolean,Void>() {
            @Override public Void apply(Boolean input) {
                if (input != null && Boolean.FALSE.equals(input) && Boolean.TRUE.equals(getAttribute(SERVICE_UP))) {
                    LOG.debug("Entity "+this+" is not reachable on JMX");
                    setAttribute(SERVICE_UP, false);
                }
                return null;
            }});
        
        // INSTANCES aggregates data for the other sensors.
        subscribe(this, KARAF_INSTANCES, new SensorEventListener<Map>() {
                @Override public void onEvent(SensorEvent<Map> event) {
                    Map map = event.getValue();
                    if (map == null) return;
                    
                    setAttribute(SERVICE_UP, "Started".equals(map.get("State")));
                    setAttribute(KARAF_ROOT, (Boolean) map.get("Is Root"));
                    setAttribute(KARAF_JAVA_OPTS, (String) map.get("JavaOpts"));
                    setAttribute(KARAF_INSTALL_LOCATION, (String) map.get("Location"));
                    setAttribute(KARAF_NAME, (String) map.get("Name"));
                    setAttribute(KARAF_PID, (Integer) map.get("Pid"));
                    setAttribute(KARAF_SSH_PORT, (Integer) map.get("Ssh Port"));
                    setAttribute(KARAF_RMI_REGISTRY_PORT, (Integer) map.get("RMI Registry Port"));
                    setAttribute(KARAF_RMI_SERVER_PORT, (Integer) map.get("RMI Server Port"));
                    setAttribute(KARAF_STATE, (String) map.get("State"));
                }});
        
    }
    
    @Override
    protected void postDriverStart() {
		super.postDriverStart();
        uploadPropertyFiles(getConfig(NAMED_PROPERTY_FILES));
        
        jmxHelper = new JmxHelper(this);
        jmxHelper.connect(JmxSensorAdapter.JMX_CONNECTION_TIMEOUT_MS);
    }
    
    @Override
    protected void preStop() {
        super.preStop();
        
        if (jmxHelper != null) jmxHelper.disconnect();
    }

    @Description("Updates the OSGi Service's properties, adding (and overriding) the given key-value pairs")
    public void updateServiceProperties(
            @NamedParameter("serviceName") @Description("Name of the OSGi service") String serviceName, 
            Map<String,String> additionalVals) {
        TabularData table = (TabularData) jmxHelper.operation(OSGI_COMPENDIUM, "getProperties", serviceName);
        
        try {
            for (Map.Entry<String, String> entry: additionalVals.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                CompositeData data = new CompositeDataSupport(
                        JmxConstants.PROPERTY_TYPE,
                        MutableMap.of(JmxConstants.KEY, key, JmxConstants.TYPE, "String", JmxConstants.VALUE, value));
                table.remove(data.getAll(new String[] {JmxConstants.KEY}));
                table.put(data);
            }
        } catch (OpenDataException e) {
            throw Exceptions.propagate(e);
        }
        
        LOG.info("Updating monterey-service configuration with changes {}", additionalVals);
        if (LOG.isTraceEnabled()) LOG.trace("Updating monterey-service configuration with new configuration {}", table);
        
        jmxHelper.operation(OSGI_COMPENDIUM, "update", serviceName, table);
    }
    
    @Description("Updates the OSGi Service's properties, adding (and overriding) the given key-value pairs")
    public void installFeature(
            @NamedParameter("featureName") @Description("Name of the feature - see org.apache.karaf:type=features#installFeature()") final String featureName) throws Exception {
        LanguageUtils.repeatUntilSuccess("Wait for Karaf, to install feature "+featureName, new Callable<Boolean>() {
            public Boolean call() {
                jmxHelper.operation(String.format(KARAF_FEATURES, getConfig(KARAF_NAME.getConfigKey())), "installFeature", featureName);
                return true;
            }});
    }

    public Map<Long,Map<String,?>> listBundles() {
        TabularData table = (TabularData) jmxHelper.operation(OSGI_BUNDLE_STATE, "listBundles");
        Map<List<?>, Map<String, Object>> map = JmxPostProcessors.tabularDataToMapOfMaps(table);
        
        Map<Long,Map<String,?>> result = Maps.newLinkedHashMap();
        for (Map.Entry<List<?>, Map<String, Object>> entry : map.entrySet()) {
            result.put((Long)entry.getKey().get(0), entry.getValue());
        }
        return result;
    }
    
    /**
     * throws URISyntaxException If bundle name is not a valid URI
     */
    @Description("Deploys the given bundle, returning the bundle id - see osgi.core:type=framework#installBundle()")
    public long installBundle(
            @NamedParameter("bundle") @Description("URI of bundle to be deployed") String bundle) throws URISyntaxException {
        
        // TODO Consider switching to use either:
        //  - org.apache.karaf:type=bundles#install(String), or 
        //  - dropping file into $RUN_DIR/deploy (but that would be async)

        URI uri = new URI(bundle);
        boolean wrap = false;
        if (WRAP_SCHEME.equals(uri.getScheme())) {
            bundle = bundle.substring(WRAP_SCHEME.length() + 1);
            uri = new URI(bundle);
            wrap = true;
        }
        if (FILE_SCHEME.equals(uri.getScheme())) {
            LOG.info("Deploying bundle {} via file copy", bundle);
            File source = new File(uri);
            File target = new File(getDriver().getRunDir(), source.getName());
            getDriver().copyFile(source, target);
            return (Long) jmxHelper.operation(OSGI_FRAMEWORK, "installBundle", (wrap ? WRAP_SCHEME + ":" : "") + FILE_SCHEME + "://" + target.getPath());
        } else {
            LOG.info("Deploying bundle {} via JMX", bundle);
            return (Long) jmxHelper.operation(OSGI_FRAMEWORK, "installBundle", (wrap ? WRAP_SCHEME + ":" : "") + bundle);
        }
    }

    @Description("Undeploys the bundle with the given id")
    public void uninstallBundle(
            @NamedParameter("bundleId") @Description("Id of the bundle") Long bundleId) {
        
        // TODO Consider switching to use either:
        //  - org.apache.karaf:type=bundles#install(String), or 
        //  - dropping file into $RUN_DIR/deploy (but that would be async)

        jmxHelper.operation(OSGI_FRAMEWORK, "uninstallBundle", bundleId);
    }

    protected void uploadPropertyFiles(Map<String,Map<String,String>> propertyFiles) {
        if (propertyFiles == null) return;
        
        for (Map.Entry<String,Map<String,String>> entry : propertyFiles.entrySet()) {
            String file = entry.getKey();
            Map<String,String> contents = entry.getValue();

            Properties props = new Properties();
            for (Map.Entry<String,String> prop : contents.entrySet()) {
                props.setProperty(prop.getKey(), prop.getValue());
            }
            
            File local = ResourceUtils.writeToTempFile(props, "karaf-"+getId(), ".cfg");
            local.setReadable(true);
            try {
                File remote = new File(getDriver().getRunDir(), file);
                getDriver().copyFile(local, remote);
            } finally {
                local.delete();
            }
        }
    }
}
