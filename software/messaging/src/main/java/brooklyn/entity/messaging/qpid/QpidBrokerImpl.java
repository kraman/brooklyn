package brooklyn.entity.messaging.qpid;

import static java.lang.String.format;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.messaging.jms.JMSBroker;
import brooklyn.event.feed.jmx.JmxAttributePollConfig;
import brooklyn.event.feed.jmx.JmxFeed;
import brooklyn.event.feed.jmx.JmxHelper;
import brooklyn.util.MutableMap;
import brooklyn.util.exceptions.Exceptions;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.Sets;

/**
 * An {@link brooklyn.entity.Entity} that represents a single Qpid broker instance, using AMQP 0-10.
 */
public class QpidBrokerImpl extends JMSBroker<QpidQueue, QpidTopic> implements QpidBroker {
    private static final Logger log = LoggerFactory.getLogger(QpidBrokerImpl.class);

    private volatile JmxFeed jmxFeed;

    public QpidBrokerImpl() {
        super();
    }
    public QpidBrokerImpl(Map properties) {
        this(properties, null);
    }
    public QpidBrokerImpl(Entity parent) {
        this(MutableMap.of(), parent);
    }
    public QpidBrokerImpl(Map properties, Entity parent) {
        super(properties, parent);
    }

    public String getVirtualHost() { return getAttribute(VIRTUAL_HOST_NAME); }
    public String getAmqpVersion() { return getAttribute(AMQP_VERSION); }
    public Integer getAmqpPort() { return getAttribute(AMQP_PORT); }

    public void setBrokerUrl() {
        String urlFormat = "amqp://guest:guest@/%s?brokerlist='tcp://%s:%d'";
        setAttribute(BROKER_URL, format(urlFormat, getAttribute(VIRTUAL_HOST_NAME), getAttribute(HOSTNAME), getAttribute(AMQP_PORT)));
    }

    public void waitForServiceUp(long duration, TimeUnit units) {
        super.waitForServiceUp(duration, units);

        // Also wait for the MBean to exist (as used when creating queue/topic)
        JmxHelper helper = new JmxHelper(this);
        try {
            String virtualHost = getConfig(QpidBroker.VIRTUAL_HOST_NAME);
            ObjectName virtualHostManager = new ObjectName(format("org.apache.qpid:type=VirtualHost.VirtualHostManager,VirtualHost=\"%s\"", virtualHost));
            helper.connect();
            helper.assertMBeanExistsEventually(virtualHostManager, units.toMillis(duration));
        } catch (MalformedObjectNameException e) {
            throw Exceptions.propagate(e);
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        } finally {
            if (helper != null) helper.disconnect();
        }
    }
    
    public QpidQueue createQueue(Map properties) {
        QpidQueue result = new QpidQueue(properties, this);
        Entities.manage(result);
        result.init();
        result.create();
        return result;
    }

    public QpidTopic createTopic(Map properties) {
        QpidTopic result = new QpidTopic(properties, this);
        Entities.manage(result);
        result.init();
        result.create();
        return result;
    }

    @Override
    public Class getDriverInterface() {
        return QpidDriver.class;
    }

    @Override
    protected Collection<Integer> getRequiredOpenPorts() {
        Set<Integer> ports = Sets.newLinkedHashSet(super.getRequiredOpenPorts());
        ports.add(getAttribute(AMQP_PORT));
        ports.add(getAttribute(HTTP_MANAGEMENT_PORT));
        log.debug("getRequiredOpenPorts detected expanded (qpid) ports {} for {}", ports, this);
        return ports;
    }

    @Override
    protected void connectSensors() {
        String serverInfoMBeanName = "org.apache.qpid:type=ServerInformation,name=ServerInformation";
        
        jmxFeed = JmxFeed.builder()
                .entity(this)
                .period(500, TimeUnit.MILLISECONDS)
                .pollAttribute(new JmxAttributePollConfig<Boolean>(SERVICE_UP)
                        .objectName(serverInfoMBeanName)
                        .attributeName("ProductVersion")
                        .onSuccess(new Function<Object,Boolean>() {
                                private boolean hasWarnedOfVersionMismatch;
                                @Override public Boolean apply(Object input) {
                                    if (input == null) return false;
                                    if (!hasWarnedOfVersionMismatch && !getConfig(QpidBroker.SUGGESTED_VERSION).equals(input)) {
                                        log.warn("Qpid version mismatch: ProductVersion is {}, requested version is {}", input, getConfig(QpidBroker.SUGGESTED_VERSION));
                                        hasWarnedOfVersionMismatch = true;
                                    }
                                    return true;
                                }})
                        .onError(Functions.constant(false)))
                .build();
    }

    @Override
    public void disconnectSensors() {
        super.disconnectSensors();
        if (jmxFeed != null) jmxFeed.stop();
    }

    @Override
    protected ToStringHelper toStringHelper() {
        return super.toStringHelper().add("amqpPort", getAmqpPort());
    }
}
