package brooklyn.location.basic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

import brooklyn.config.BrooklynProperties;
import brooklyn.location.Location;
import brooklyn.location.LocationDefinition;
import brooklyn.management.internal.LocalManagementContext;

public class LocationRegistryTest {
    
    private static final Logger log = LoggerFactory.getLogger(LocationRegistryTest.class);
    
    private LocalManagementContext mgmt;
    private LocationDefinition locdef;
    
    @Test
    public void testNamedLocationsPropertyDefinedLocations() {
        BrooklynProperties properties = BrooklynProperties.Factory.newEmpty();
        properties.put("brooklyn.location.named.foo", "byon:(hosts=\"root@192.168.1.{1,2,3,4}\")");
        properties.put("brooklyn.location.named.foo.privateKeyFile", "~/.ssh/foo.id_rsa");
        mgmt = new LocalManagementContext(properties);
        log.info("foo properties gave defined locations: "+mgmt.getLocationRegistry().getDefinedLocations());
        locdef = mgmt.getLocationRegistry().getDefinedLocationByName("foo");
        Assert.assertNotNull(locdef, "Expected 'foo' location; but had "+mgmt.getLocationRegistry().getDefinedLocations());
        Assert.assertEquals(locdef.getConfig().get("privateKeyFile"), "~/.ssh/foo.id_rsa");
    }
    
    @Test(dependsOnMethods="testNamedLocationsPropertyDefinedLocations")
    public void testResolvesByNamedAndId() {
        log.info("testResovlesBy has defined locations: "+mgmt.getLocationRegistry().getDefinedLocations());
        
        Location l = mgmt.getLocationRegistry().resolve("named:foo");
        Assert.assertNotNull(l);
        Assert.assertEquals(l.getLocationProperty("privateKeyFile"), "~/.ssh/foo.id_rsa");
        
        l = mgmt.getLocationRegistry().resolve("foo");
        Assert.assertNotNull(l);
        Assert.assertEquals(l.getLocationProperty("privateKeyFile"), "~/.ssh/foo.id_rsa");
        
        l = mgmt.getLocationRegistry().resolve("id:"+locdef.getId());
        Assert.assertNotNull(l);
        Assert.assertEquals(l.getLocationProperty("privateKeyFile"), "~/.ssh/foo.id_rsa");
        
        l = mgmt.getLocationRegistry().resolve(locdef.getId());
        Assert.assertNotNull(l);
        Assert.assertEquals(l.getLocationProperty("privateKeyFile"), "~/.ssh/foo.id_rsa");
    }

    @Test
    public void testSetupForTesting() {
        LocalManagementContext mgmt = new LocalManagementContext();
        BasicLocationRegistry.setupLocationRegistryForTesting(mgmt);
        Assert.assertNotNull(mgmt.getLocationRegistry().getDefinedLocationByName("localhost"));
    }

    @Test
    public void testCircularReference() {
        BrooklynProperties properties = BrooklynProperties.Factory.newEmpty();
        properties.put("brooklyn.location.named.bar", "named:bar");
        LocalManagementContext mgmt = new LocalManagementContext(properties);
        log.info("bar properties gave defined locations: "+mgmt.getLocationRegistry().getDefinedLocations());
        boolean resolved = false;
        try {
            mgmt.getLocationRegistry().resolve("bar");
            resolved = true;
        } catch (IllegalStateException e) {
            //expected
            log.info("bar properties correctly caught circular reference: "+e);
        }
        if (resolved)
            // probably won't happen, if test fails will loop endlessly above
            Assert.fail("Circular reference resolved location");
    }
    
}
