package brooklyn.demo;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.StringConfigMap;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.dns.geoscaling.GeoscalingDnsService;
import brooklyn.entity.group.DynamicFabric;
import brooklyn.entity.proxy.AbstractController;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.webapp.ElasticJavaWebAppService;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.extras.cloudfoundry.CloudFoundryJavaWebAppCluster;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.location.basic.PortRanges;
import brooklyn.util.CommandLineUtil;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class GlobalWebFabricExample extends AbstractApplication {

    public static final Logger log = LoggerFactory.getLogger(GlobalWebFabricExample.class);
    
    public static final String WAR_PATH = "classpath://hello-world-webapp.war";
    
    static final List<String> DEFAULT_LOCATIONS = ImmutableList.of(
            "aws-ec2:eu-west-1",
            "aws-ec2:ap-southeast-1",
            "aws-ec2:us-west-1" 
//            "cloudfoundry:https://api.aws.af.cm/",
        );

    @Override
    public void init() {
        StringConfigMap config = getManagementContext().getConfig();
        
        GeoscalingDnsService geoDns = addChild(EntitySpecs.spec(GeoscalingDnsService.class)
                .displayName("GeoScaling DNS")
                .configure("username", checkNotNull(config.getFirst("brooklyn.geoscaling.username"), "username"))
                .configure("password", checkNotNull(config.getFirst("brooklyn.geoscaling.password"), "password"))
                .configure("primaryDomainName", checkNotNull(config.getFirst("brooklyn.geoscaling.primaryDomain"), "primaryDomain")) 
                .configure("smartSubdomainName", "brooklyn"));
        
        DynamicFabric webFabric = addChild(EntitySpecs.spec(DynamicFabric.class)
                .displayName("Web Fabric")
                .configure(DynamicFabric.FACTORY, new ElasticJavaWebAppService.Factory())
                
                //specify the WAR file to use
                .configure(ElasticJavaWebAppService.ROOT_WAR, WAR_PATH)
                
                //load-balancer instances must run on 80 to work with GeoDNS (default is 8000)
                .configure(AbstractController.PROXY_HTTP_PORT, PortRanges.fromInteger(80))
                
                //CloudFoundry requires to be told what URL it should listen to, which is chosen by the GeoDNS service
                .configure(CloudFoundryJavaWebAppCluster.HOSTNAME_TO_USE_FOR_URL,
                        DependentConfiguration.attributeWhenReady(geoDns, Attributes.HOSTNAME)));

        //tell GeoDNS what to monitor
        geoDns.setTargetEntityProvider(webFabric);
    }

    public static void main(String[] argv) {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String locations = CommandLineUtil.getCommandLineOption(args, "--locations", Joiner.on(",").join(DEFAULT_LOCATIONS));

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                .application(EntitySpecs.appSpec(GlobalWebFabricExample.class).displayName("Brooklyn Global Web Fabric Example"))
                .webconsolePort(port)
                .locations(Arrays.asList(locations))
                .start();
         
        Entities.dumpInfo(launcher.getApplications());
    }
}
