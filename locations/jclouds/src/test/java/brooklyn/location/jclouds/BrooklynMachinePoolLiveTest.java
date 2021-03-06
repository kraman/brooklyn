package brooklyn.location.jclouds;

import brooklyn.config.BrooklynProperties;
import brooklyn.location.basic.LocationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import brooklyn.location.basic.SshMachineLocation;
import brooklyn.location.jclouds.BrooklynMachinePool;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsResolver;
import brooklyn.location.jclouds.pool.MachinePoolPredicates;
import brooklyn.location.jclouds.pool.MachineSet;
import brooklyn.location.jclouds.pool.ReusableMachineTemplate;

public class BrooklynMachinePoolLiveTest {

    public static final Logger log = LoggerFactory.getLogger(BrooklynMachinePoolLiveTest.class);
    
    public static class SamplePool extends BrooklynMachinePool {
        public SamplePool(JcloudsLocation l) {
            super(l);
        }

        public final static ReusableMachineTemplate 
            USUAL_VM = 
                new ReusableMachineTemplate("usual").templateOwnedByMe().
                tagOptional("tagForUsualVm").
                metadataOptional("metadataForUsualVm", "12345").
                minRam(1024).minCores(2);

        public final static ReusableMachineTemplate 
            ANYONE_NOT_TINY_VM = 
                new ReusableMachineTemplate("anyone").
                minRam(512).minCores(1).strict(false);

        public static final ReusableMachineTemplate 
            VM_LARGE1 = 
                new ReusableMachineTemplate("vm.large1").templateOwnedByMe().
                minRam(16384).minCores(4),
            VM_SMALL1 = 
                new ReusableMachineTemplate("vm.small1").templateOwnedByMe().smallest();
        
        { registerTemplates(USUAL_VM, ANYONE_NOT_TINY_VM, VM_LARGE1, VM_SMALL1); }
    }
    
    @Test(groups="Live")
    public void buildClaimAndDestroy() {
        SamplePool p = new SamplePool(resolve("aws-ec2:us-west-1"));
        log.info("buildClaimAndDestroy: created pool");
        p.refresh();
        log.info("buildClaimAndDestroy: refreshed pool");
        p.ensureExists(2, SamplePool.USUAL_VM);
        log.info("buildClaimAndDestroy: ensure have 2");
        SshMachineLocation l = p.obtain(SamplePool.USUAL_VM);
        Assert.assertNotNull(l);
        log.info("buildClaimAndDestroy: claimed 1");
        MachineSet unclaimedUsual = p.unclaimed(MachinePoolPredicates.matching(SamplePool.USUAL_VM));
        log.info("buildClaimAndDestroy: unclaimed now "+unclaimedUsual);
        Assert.assertTrue(!unclaimedUsual.isEmpty(), "should have been unclaimed machines (can fail if there are some we cannot connect to, ie blacklisted)");
        p.destroy(unclaimedUsual);
        p.destroy(l);
        unclaimedUsual = p.unclaimed(MachinePoolPredicates.matching(SamplePool.USUAL_VM));
        log.info("buildClaimAndDestroy: destroyed, unclaimed now "+unclaimedUsual);
        log.info("end");
    }
    

    private static JcloudsLocation resolve(String spec) {
        BrooklynProperties brooklynProperties = BrooklynProperties.Factory.newDefault();
        return (JcloudsLocation) new LocationRegistry(brooklynProperties).resolve(JcloudsResolver.JCLOUDS+":"+spec);
    }
}
