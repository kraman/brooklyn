package brooklyn.location.jclouds;

import static brooklyn.util.GroovyJavaMethods.truth;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.util.ComputeServiceUtils.execHttpResponse;
import static org.jclouds.scriptbuilder.domain.Statements.appendFile;
import static org.jclouds.scriptbuilder.domain.Statements.exec;
import static org.jclouds.scriptbuilder.domain.Statements.interpret;
import static org.jclouds.scriptbuilder.domain.Statements.newStatementList;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.ec2.AWSEC2ApiMetadata;
import org.jclouds.aws.ec2.AWSEC2Client;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OperatingSystem;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.predicates.OperatingSystemPredicates;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.compute.domain.PasswordDataAndPrivateKey;
import org.jclouds.ec2.compute.functions.WindowsLoginCredentialsFromEncryptedData;
import org.jclouds.ec2.domain.PasswordData;
import org.jclouds.ec2.services.WindowsClient;
import org.jclouds.encryption.bouncycastle.config.BouncyCastleCryptoModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.Statements;
import org.jclouds.ssh.SshClient;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.basic.Entities;
import brooklyn.util.MutableMap;
import brooklyn.util.internal.Repeater;
import brooklyn.util.config.ConfigBag;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.inject.Module;

public class JcloudsUtil implements JcloudsLocationConfig {
    
    // TODO Review what utility methods are needed, and what is now supported in jclouds 1.1
    
    private static final Logger LOG = LoggerFactory.getLogger(JcloudsUtil.class);
    
    public static String APT_INSTALL = "apt-get install -f -y -qq --force-yes";

    public static String installAfterUpdatingIfNotPresent(String cmd) {
       String aptInstallCmd = APT_INSTALL + " " + cmd;
       return String.format("which %s || (%s || (apt-get update && %s))", cmd, aptInstallCmd, aptInstallCmd);
    }

    public static Predicate<NodeMetadata> predicateMatchingById(final NodeMetadata node) {
        return predicateMatchingById(node.getId());
    }

    public static Predicate<NodeMetadata> predicateMatchingById(final String id) {
        Predicate<NodeMetadata> nodePredicate = new Predicate<NodeMetadata>() {
            @Override public boolean apply(NodeMetadata arg0) {
                return id.equals(arg0.getId());
            }
            @Override public String toString() {
                return "node.id=="+id;
            }
        };
        return nodePredicate;
    }

    public static Statement authorizePortInIpTables(int port) {
        // TODO gogrid rules only allow ports 22, 3389, 80 and 443.
        // the first rule will be ignored, so we have to apply this
        // directly
        return Statements.newStatementList(// just in case iptables are being used, try to open 8080
                exec("iptables -I INPUT 1 -p tcp --dport " + port + " -j ACCEPT"),//
                exec("iptables -I RH-Firewall-1-INPUT 1 -p tcp --dport " + port + " -j ACCEPT"),//
                exec("iptables-save"));
    }

    /**
     * @throws RunScriptOnNodesException
     * @throws IllegalStateException     If do not find exactly one matching node
     */
    public static ExecResponse runScriptOnNode(ComputeService computeService, NodeMetadata node, Statement statement, String scriptName) throws RunScriptOnNodesException {
        // TODO Includes workaround for NodeMetadata's equals/hashcode method being wrong.
        
        Map<? extends NodeMetadata, ExecResponse> scriptResults = computeService.runScriptOnNodesMatching(
                JcloudsUtil.predicateMatchingById(node), 
                statement,
                new RunScriptOptions().nameTask(scriptName));
        if (scriptResults.isEmpty()) {
            throw new IllegalStateException("No matching node found when executing script "+scriptName+": expected="+node);
        } else if (scriptResults.size() > 1) {
            throw new IllegalStateException("Multiple nodes matched predicate: id="+node.getId()+"; expected="+node+"; actual="+scriptResults.keySet());
        } else {
            return Iterables.getOnlyElement(scriptResults.values());
        }
    }
    
    public static final Statement APT_RUN_SCRIPT = newStatementList(//
          exec(installAfterUpdatingIfNotPresent("curl")),//
          exec("(which java && java -fullversion 2>&1|egrep -q 1.6 ) ||"),//
          execHttpResponse(URI.create("http://whirr.s3.amazonaws.com/0.2.0-incubating-SNAPSHOT/sun/java/install")),//
          exec(new StringBuilder()//
                .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")//
                // jeos hasn't enough room!
                .append("rm -rf /var/cache/apt /usr/lib/vmware-tools\n")//
                .append("echo \"export PATH=\\\"$JAVA_HOME/bin/:$PATH\\\"\" >> /root/.bashrc")//
                .toString()));

    public static final Statement YUM_RUN_SCRIPT = newStatementList(
          exec("which curl ||yum --nogpgcheck -y install curl"),//
          exec("(which java && java -fullversion 2>&1|egrep -q 1.6 ) ||"),//
          execHttpResponse(URI.create("http://whirr.s3.amazonaws.com/0.2.0-incubating-SNAPSHOT/sun/java/install")),//
          exec(new StringBuilder()//
                .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n") //
                .append("echo \"export PATH=\\\"$JAVA_HOME/bin/:$PATH\\\"\" >> /root/.bashrc")//
                .toString()));

    public static final Statement ZYPPER_RUN_SCRIPT = exec(new StringBuilder()//
          .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")//
          .append("which curl || zypper install curl\n")//
          .append("(which java && java -fullversion 2>&1|egrep -q 1.6 ) || zypper install java-1.6.0-openjdk\n")//
          .toString());

    // Code taken from RunScriptData
    public static Statement installJavaAndCurl(OperatingSystem os) {
       if (os == null || OperatingSystemPredicates.supportsApt().apply(os))
          return APT_RUN_SCRIPT;
       else if (OperatingSystemPredicates.supportsYum().apply(os))
          return YUM_RUN_SCRIPT;
       else if (OperatingSystemPredicates.supportsZypper().apply(os))
          return ZYPPER_RUN_SCRIPT;
       else
          throw new IllegalArgumentException("don't know how to handle" + os.toString());
    }

    static Map<Properties,ComputeService> cachedComputeServices = new ConcurrentHashMap<Properties,ComputeService> ();
    
    /** @deprecated since 0.5.0 pass ConfigBag instead */
    public static ComputeService buildOrFindComputeService(Map<String,? extends Object> conf) {
        return buildComputeService(conf, MutableMap.of(), true);
    }
    /** @deprecated since 0.5.0 pass ConfigBag instead */
    public static ComputeService buildOrFindComputeService(Map<String,? extends Object> conf, Map unusedConf) {
        return buildComputeService(conf, unusedConf, true);
    }
    /** @deprecated since 0.5.0 pass ConfigBag instead */
    public static ComputeService buildComputeService(Map<String,? extends Object> conf) {
        return buildComputeService(conf, MutableMap.of());
    }
    /** @deprecated since 0.5.0 pass ConfigBag instead */
    public static ComputeService buildComputeService(Map<String,? extends Object> conf, Map unusedConf) {
        return buildComputeService(conf, unusedConf, false);
    }
    /** @deprecated since 0.5.0 pass ConfigBag instead */
    public static ComputeService buildComputeService(Map<String,? extends Object> conf, Map unusedConf, boolean allowReuse) {
        ConfigBag confBag = new ConfigBag().putAll(conf).markAll(Sets.difference(conf.keySet(), unusedConf.keySet()));
        return findComputeService(confBag, allowReuse);
    }
    public static ComputeService findComputeService(ConfigBag conf) {
        return findComputeService(conf, true);
    }
    public static ComputeService findComputeService(ConfigBag conf, boolean allowReuse) {
        Properties properties = new Properties();
        String provider = conf.get(CLOUD_PROVIDER);
        String identity = conf.get(ACCESS_IDENTITY);
        String credential = conf.get(ACCESS_CREDENTIAL);
        
        properties.setProperty(Constants.PROPERTY_PROVIDER, provider);
        properties.setProperty(Constants.PROPERTY_IDENTITY, identity);
        properties.setProperty(Constants.PROPERTY_CREDENTIAL, credential);
        properties.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, Boolean.toString(true));
        properties.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, Boolean.toString(true));
                
        // Enable aws-ec2 lazy image fetching, if given a specific imageId; otherwise customize for specific owners; or all as a last resort
        // See https://issues.apache.org/jira/browse/WHIRR-416
        if ("aws-ec2".equals(provider)) {
            // TODO convert AWS-only flags to config keys
            if (truth(conf.get(IMAGE_ID))) {
                properties.setProperty(PROPERTY_EC2_AMI_QUERY, "");
                properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");
            } else if (truth(conf.getStringKey("imageOwner"))) {
                properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id="+conf.getStringKey("imageOwner")+";state=available;image-type=machine");
            } else if (truth(conf.getStringKey("anyOwner"))) {
                // set `anyOwner: true` to override the default query (which is restricted to certain owners as per below), 
                // allowing the AMI query to bind to any machine
                // (note however, we sometimes pick defaults in JcloudsLocationFactory);
                // (and be careful, this can give a LOT of data back, taking several minutes,
                // and requiring extra memory allocated on the command-line)
                properties.setProperty(PROPERTY_EC2_AMI_QUERY, "state=available;image-type=machine");
                /*
                 * by default the following filters are applied:
                 * Filter.1.Name=owner-id&Filter.1.Value.1=137112412989&
                 * Filter.1.Value.2=063491364108&
                 * Filter.1.Value.3=099720109477&
                 * Filter.1.Value.4=411009282317&
                 * Filter.2.Name=state&Filter.2.Value.1=available&
                 * Filter.3.Name=image-type&Filter.3.Value.1=machine&
                 */
            }
        }

        String endpoint = (String) conf.get(CLOUD_ENDPOINT);
        if (!truth(endpoint)) endpoint = getDeprecatedProperty(conf, Constants.PROPERTY_ENDPOINT);
        if (truth(endpoint)) properties.setProperty(Constants.PROPERTY_ENDPOINT, endpoint);

        if (allowReuse) {
            ComputeService result = cachedComputeServices.get(properties);
            if (result!=null) {
                LOG.debug("jclouds ComputeService cache hit for compute service, for "+Entities.sanitize(properties));
                return result;
            }
            LOG.debug("jclouds ComputeService cache miss for compute service, creating, for "+Entities.sanitize(properties));
        }
        
        Iterable<Module> modules = ImmutableSet.<Module> of(
                new SshjSshClientModule(), 
                new SLF4JLoggingModule(),
                new BouncyCastleCryptoModule());

        ComputeServiceContext computeServiceContext = ContextBuilder.newBuilder(provider)
                .modules(modules)
                .credentials(identity, credential)
                .overrides(properties)
                .build(ComputeServiceContext.class);
        final ComputeService computeService = computeServiceContext.getComputeService();
                
        if (allowReuse) {
            synchronized (cachedComputeServices) {
                ComputeService result = cachedComputeServices.get(properties);
                if (result != null) {
                    LOG.debug("jclouds ComputeService cache recovery for compute service, for "+Entities.sanitize(properties));
                    //keep the old one, discard the new one
                    computeService.getContext().close();
                    return result;
                }
                LOG.debug("jclouds ComputeService created "+computeService+", adding to cache, for "+Entities.sanitize(properties));
                cachedComputeServices.put(properties, computeService);
            }
        }
        
        return computeService;
     }
     
     protected static String getDeprecatedProperty(ConfigBag conf, String key) {
        if (conf.containsKey(key)) {
            LOG.warn("Jclouds using deprecated brooklyn-jclouds property "+key+": "+Entities.sanitize(conf.getAllConfig()));
            return (String) conf.getStringKey(key);
        } else {
            return null;
        }
    }

    // Do this so that if there's a problem with our USERNAME's ssh key, we can still get in to check
     // TODO Once we're really confident there are not going to be regular problems, then delete this
     public static Statement addAuthorizedKeysToRoot(File publicKeyFile) throws IOException {
         String publicKey = Files.toString(publicKeyFile, Charsets.UTF_8);
         return addAuthorizedKeysToRoot(publicKey);
     }
     
     public static Statement addAuthorizedKeysToRoot(String publicKey) {
         return newStatementList(
                 appendFile("/root/.ssh/authorized_keys", Splitter.on('\n').split(publicKey)),
                 interpret("chmod 600 /root/.ssh/authorized_keys"));
     }

    public static String getFirstReachableAddress(ComputeServiceContext context, NodeMetadata node) {
        // FIXME calling client.connect() will retry by default 7 times, but each of those attempts can
        // be fast if just getting an IOException. We want to wait for some non-hard-coded period of time 
        // (e.g. 2 minutes?) for us to connect.
        // Should we be using `TemplateOptions.blockOnPort(22, 120)`? Also see:
        //   [12/02/2013 12:21:55] Andrea Turli: https://github.com/jclouds/jclouds/pull/895
        //   [12/02/2013 12:22:25] Andrea Turli: and here https://issues.apache.org/jira/browse/WHIRR-420
        //   jclouds.ssh.max-retries
        //   jclouds.ssh.retry-auth
        // ^^^ that looks fixed now. BUT do we need this method (as when we call it, we connect just afterwards)
        // and TODO this method doesn't do what the name suggests; there is no iterating through addresses to see what is reachable
        final SshClient client = context.utils().sshForNode().apply(node);
        final AtomicReference<String> result = new AtomicReference<String>();
        new Repeater()
                .every(1000, TimeUnit.MILLISECONDS)
                .limitTimeTo(120*1000, TimeUnit.MILLISECONDS)
                .rethrowException()
                .repeat(new Callable<Void>() {
                        public Void call() {
                            try {
                                client.connect();
                                result.set(client.getHostAddress());
                                return null;
                            } finally {
                                client.disconnect();
                            }
                        }})
                .until(new Callable<Boolean>() {
                        public Boolean call() {
                            return result.get() != null;
                        }})
                .run();
        
        return result.get();
    }
    
    // Suggest at least 15 minutes for timeout
    public static String waitForPasswordOnAws(ComputeService computeService, final NodeMetadata node, long timeout, TimeUnit timeUnit) throws TimeoutException {
        ComputeServiceContext computeServiceContext = computeService.getContext();
        AWSEC2Client ec2Client = computeServiceContext.unwrap(AWSEC2ApiMetadata.CONTEXT_TOKEN).getApi();
        final WindowsClient client = ec2Client.getWindowsServices();
        final String region = node.getLocation().getParent().getId();
      
        // The Administrator password will take some time before it is ready - Amazon says sometimes 15 minutes.
        // So we create a predicate that tests if the password is ready, and wrap it in a retryable predicate.
        Predicate<String> passwordReady = new Predicate<String>() {
            @Override public boolean apply(String s) {
                if (Strings.isNullOrEmpty(s)) return false;
                PasswordData data = client.getPasswordDataInRegion(region, s);
                if (data == null) return false;
                return !Strings.isNullOrEmpty(data.getPasswordData());
            }
        };
        
        LOG.info("Waiting for password, for "+node.getProviderId()+":"+node.getId());
        RetryablePredicate<String> passwordReadyRetryable = new RetryablePredicate<String>(passwordReady, timeUnit.toMillis(timeout), 10*1000, TimeUnit.MILLISECONDS);
        boolean ready = passwordReadyRetryable.apply(node.getProviderId());
        if (!ready) throw new TimeoutException("Password not available for "+node+" in region "+region+" after "+timeout+" "+timeUnit.name());

        // Now pull together Amazon's encrypted password blob, and the private key that jclouds generated
        PasswordDataAndPrivateKey dataAndKey = new PasswordDataAndPrivateKey(
                client.getPasswordDataInRegion(region, node.getProviderId()),
                node.getCredentials().getPrivateKey());

        // And apply it to the decryption function
        WindowsLoginCredentialsFromEncryptedData f = computeServiceContext.getUtils().getInjector().getInstance(WindowsLoginCredentialsFromEncryptedData.class);
        LoginCredentials credentials = f.apply(dataAndKey);

        return credentials.getPassword();
    }
}
