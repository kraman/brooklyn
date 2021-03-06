package brooklyn.entity.drivers;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.basic.Entities;
import brooklyn.entity.drivers.ReflectiveEntityDriverFactoryTest.MyDriver;
import brooklyn.entity.drivers.ReflectiveEntityDriverFactoryTest.MyDriverDependentEntity;
import brooklyn.entity.drivers.RegistryEntityDriverFactoryTest.MyOtherSshDriver;
import brooklyn.location.basic.SshMachineLocation;
import brooklyn.management.ManagementContext;
import brooklyn.util.MutableMap;

public class EntityDriverRegistryTest {

    private ManagementContext managementContext;
    private SshMachineLocation sshLocation;

    @BeforeMethod
    public void setUp() throws Exception {
        managementContext = Entities.newManagementContext();
        sshLocation = new SshMachineLocation(MutableMap.of("address", "localhost"));
    }
    
    @Test
    public void testInstantiatesRegisteredDriver() throws Exception {
        managementContext.getEntityDriverManager().registerDriver(MyDriver.class, SshMachineLocation.class, MyOtherSshDriver.class);
        DriverDependentEntity<MyDriver> entity = new MyDriverDependentEntity<MyDriver>(MyDriver.class);
        MyDriver driver = managementContext.getEntityDriverManager().build(entity, sshLocation);
        assertTrue(driver instanceof MyOtherSshDriver);
    }
}
