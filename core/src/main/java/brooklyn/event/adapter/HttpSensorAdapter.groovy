package brooklyn.event.adapter

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.event.Sensor
import brooklyn.util.flags.FlagUtils

/**
 * @deprecated See brooklyn.event.feed.http.HttpFeed
 */
@Deprecated
public class HttpSensorAdapter extends AbstractSensorAdapter {

	public static final Logger log = LoggerFactory.getLogger(HttpSensorAdapter.class)

	protected String baseUrl
	protected final HttpPollHelper poller;
	protected final Map urlVars=[:]

    public HttpSensorAdapter(Map flags=[:], String url) {
        super(flags)
        this.baseUrl = url
        poller = new HttpPollHelper(this);
        poller.init();
    }

	protected boolean isPost = false;
		
	protected boolean isConnected() { isActivated() && poller!=null && poller.getLastWasSuccessful() }
	
	// TODO does not currenlty support post or parameter conveniences ... but 'suburl' below shows how this could work
	/** returns new adapter which will POST the vars */
	public HttpSensorAdapter post() {  }

	/** returns a new adapter, registered, with the given additional parameters (for 'get' or 'post') */ 
	public HttpSensorAdapter vars(Map vars) {
		def newFlags = FlagUtils.getFieldsWithValues(this)
		def newAdapter = new HttpSensorAdapter(newFlags, baseUrl)
		newAdapter.urlVars << vars
		if (registry) return registry.register(newAdapter);
		return newAdapter;
	}

	/** returns a new adapter, registered, for accessing a child URL */
	public HttpSensorAdapter suburl(Map flags=[:], String urlExtension) {
		def newFlags = FlagUtils.getFieldsWithValues(this)+flags
		def newUrl = baseUrl;
		if (newUrl.endsWith("/") && urlExtension.startsWith("/")) newUrl = newUrl.substring(0, newUrl.length()-1);
		newUrl += urlExtension
		return registry.register(new HttpSensorAdapter(newFlags, newUrl));
	}

	/** closure will run in an HttpResponseContext, default value is the string content */
	public void poll(Sensor s, Closure c={it}) {
		poller.addSensor(s, c);
	}

}
