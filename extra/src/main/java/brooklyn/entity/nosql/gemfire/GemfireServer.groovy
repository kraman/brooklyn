package brooklyn.entity.nosql.gemfire

import groovy.lang.MetaClass

import java.net.URL
import java.util.Collection
import java.util.Map

import brooklyn.entity.Entity
import brooklyn.entity.basic.AbstractService
import brooklyn.entity.group.AbstractController
import brooklyn.entity.trait.Startable
import brooklyn.event.adapter.AttributePoller
import brooklyn.event.adapter.HttpSensorAdapter
import brooklyn.event.adapter.ValueProvider
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.event.basic.BasicConfigKey
import brooklyn.location.Location
import brooklyn.location.MachineLocation
import brooklyn.location.basic.SshMachineLocation
import brooklyn.util.SshBasedAppSetup

class GemfireServer extends AbstractService implements Startable {

    public static final BasicConfigKey<String> INSTALL_DIR = [String.class, "gemfire.server.installDir", "Gemfire installation directory" ]
    public static final BasicConfigKey<File> CONFIG_FILE = [File.class, "gemfire.server.configFile", "Gemfire configuration file" ]
    public static final BasicConfigKey<File> JAR_FILE = [File.class, "gemfire.server.jarFile", "Gemfire jar file" ]
    public static final BasicConfigKey<Integer> SUGGESTED_HUB_PORT = [Integer.class, "gemfire.server.suggestedHubPort", "Gemfire gateway hub port", 11111 ]
    public static final BasicConfigKey<File> LICENSE = [File.class, "gemfire.server.license", "Gemfire license file" ]

    public static final BasicAttributeSensor<String> HOSTNAME = [ String.class, "gemfire.server.hostname", "Gemfire server hostname" ]
    public static final BasicAttributeSensor<Integer> HUB_PORT = [ Integer.class, "gemfire.server.hubPort", "Gemfire gateway hub port" ]
    public static final BasicAttributeSensor<URL> CONTROL_URL = [ URL.class, "gemfire.server.controlUrl", "URL for perfoming management actions" ]

    private static final int CONTROL_PORT_VAL = 8084    
    transient HttpSensorAdapter httpAdapter
    transient AttributePoller attributePoller

    public GemfireServer(Map properties=[:], Entity owner=null) {
        super(properties, owner)
    }

    @Override
    public void stop() {
        attributePoller.close()
        super.stop()
    }

    @Override
    public void restart() {
        throw new UnsupportedOperationException()
    }
    
    public SshBasedAppSetup getSshBasedSetup(SshMachineLocation loc) {
        return GemfireSetup.newInstance(this, loc)
    }
    
    public void startInLocation(SshMachineLocation machine) {
        super.startInLocation(machine)
        
        int hubPort = getConfig(SUGGESTED_HUB_PORT)
        setAttribute(HUB_PORT, hubPort)
        setAttribute(HOSTNAME, machine.address.hostAddress)
        setAttribute(CONTROL_URL, "http://"+machine.address.hostAddress+":"+CONTROL_PORT_VAL)
        
        attributePoller = new AttributePoller(this)
        httpAdapter = new HttpSensorAdapter(this)
        attributePoller.addSensor(SERVICE_UP, { computeNodeUp() } as ValueProvider)
    }

    private boolean computeNodeUp() {
        String url = getAttribute(AbstractController.URL)
        ValueProvider<Integer> provider = httpAdapter.newStatusValueProvider(url)
        try {
            Integer statusCode = provider.compute()
            return (statusCode >= 200 && statusCode <= 299)
        } catch (IOException ioe) {
            return false
        }
    }
    
    // TODO Find a better way to detect early death of process.
    public void waitForEntityStart() throws IllegalStateException {
        log.debug "waiting to ensure $this doesn't abort prematurely"
        long startTime = System.currentTimeMillis()
        long waitTime = startTime + 75000 // FIXME magic number
        boolean isRunningResult = false;
        while (!isRunningResult && System.currentTimeMillis() < waitTime) {
            Thread.sleep 3000 // FIXME magic number
            isRunningResult = setup.isRunning()
            log.debug "checked $this, running result $isRunningResult"
        }
        if (!isRunningResult) {
            setAttribute(SERVICE_STATUS, "failed")
            throw new IllegalStateException("$this aborted soon after startup")
        }
    }

    public void shutdownInLocation(MachineLocation machine) {
        if (setup) setup.stop()
        
        // Only release this machine if we ourselves provisioned it (e.g. it might be running multiple services)
        provisioningLoc?.release(machine)
    }
    
    public void addGateways(Collection<GatewayConnectionDetails> gateways) {
        String urlstr = getAttribute(CONTROL_URL)+"/add?id=EU&endpointId=EU-1&port=11111&host=ec2-184-73-13-122.compute-1.amazonaws.com"
        URL url = new URL(urlstr)
        HttpURLConnection connection = url.openConnection()
        connection.connect()
        int responseCode = connection.getResponseCode()
        if (responseCode < 200 || responseCode >= 300) {
            throw new IllegalStateException("Failed to add gateway to server, response code $responseCode for using $url")
        }

    }
}