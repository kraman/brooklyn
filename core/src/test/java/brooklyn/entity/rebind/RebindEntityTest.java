package brooklyn.entity.rebind;

import static brooklyn.test.EntityTestUtils.assertAttributeEquals;
import static brooklyn.test.EntityTestUtils.assertConfigEquals;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.config.ConfigKey;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.BasicGroup;
import brooklyn.entity.basic.BasicGroupImpl;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.proxying.ImplementedBy;
import brooklyn.entity.rebind.RebindLocationTest.MyLocation;
import brooklyn.entity.trait.Startable;
import brooklyn.event.AttributeSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.BasicAttributeSensor;
import brooklyn.event.basic.BasicConfigKey;
import brooklyn.event.basic.BasicSensorEvent;
import brooklyn.location.Location;
import brooklyn.management.internal.LocalManagementContext;
import brooklyn.mementos.EntityMemento;
import brooklyn.test.TestUtils;
import brooklyn.test.entity.TestEntity;
import brooklyn.test.entity.TestEntityImpl;
import brooklyn.util.MutableMap;
import brooklyn.util.exceptions.RuntimeInterruptedException;
import brooklyn.util.flags.SetFromFlag;

import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

public class RebindEntityTest {

    // FIXME Add test about dependent configuration serialization?!
    
    // TODO Convert, so not calling entity constructors
    
    private static final long TIMEOUT_MS = 10*1000;
    
    private ClassLoader classLoader = getClass().getClassLoader();
    private LocalManagementContext managementContext;
    private MyApplication origApp;
    private File mementoDir;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        mementoDir = Files.createTempDir();
        managementContext = RebindTestUtils.newPersistingManagementContext(mementoDir, classLoader, 1);
        origApp = new MyApplicationImpl();
    }

    @AfterMethod(alwaysRun=true)
    public void tearDown() throws Exception {
        managementContext.terminate();
        if (mementoDir != null) RebindTestUtils.deleteMementoDir(mementoDir);
    }

    @Test
    public void testRestoresSimpleApp() throws Exception {
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        assertNotSame(newApp, origApp);
        assertEquals(newApp.getId(), origApp.getId());
    }
    
    @Test
    public void testRestoresEntityHierarchy() throws Exception {
        MyEntity origE = new MyEntityImpl(origApp);
        MyEntity origE2 = new MyEntityImpl(origE);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();

        // Assert has expected config/fields
        assertEquals(newApp.getId(), origApp.getId());
        
        assertEquals(newApp.getChildren().size(), 1);
        MyEntity newE = (MyEntity) Iterables.get(newApp.getChildren(), 0);
        assertEquals(newE.getId(), origE.getId());

        assertEquals(newE.getChildren().size(), 1);
        MyEntity newE2 = (MyEntity) Iterables.get(newE.getChildren(), 0);
        assertEquals(newE2.getId(), origE2.getId());
        
        assertNotSame(origApp, newApp);
        assertNotSame(origApp.getManagementContext(), newApp.getManagementContext());
        assertNotSame(origE, newE);
        assertNotSame(origE2, newE2);
    }
    
    @Test
    public void testRestoresGroupMembers() throws Exception {
        MyEntity origE = new MyEntityImpl(origApp);
        MyEntity origE2 = new MyEntityImpl(origApp);
        BasicGroup origG = new BasicGroupImpl(origApp);
        origG.addMember(origE);
        origG.addMember(origE2);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        
        BasicGroup newG = (BasicGroup) Iterables.find(newApp.getChildren(), Predicates.instanceOf(BasicGroup.class));
        Iterable<Entity> newEs = Iterables.filter(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        assertEquals(ImmutableSet.copyOf(newG.getMembers()), ImmutableSet.copyOf(newEs));
    }
    
    @Test
    public void testRestoresEntityConfig() throws Exception {
        MyEntity origE = new MyEntityImpl(MutableMap.of("myconfig", "myval"), origApp);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        assertEquals(newE.getConfig(MyEntity.MY_CONFIG), "myval");
    }
    
    @Test
    public void testRestoresEntitySensors() throws Exception {
        AttributeSensor<String> myCustomAttribute = new BasicAttributeSensor<String>(String.class, "my.custom.attribute");
        
        MyEntity origE = new MyEntityImpl(origApp);
        Entities.startManagement(origApp, managementContext);
        origE.setAttribute(myCustomAttribute, "myval");
        
        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        assertEquals(newE.getAttribute(myCustomAttribute), "myval");
    }
    
    @Test
    public void testRestoresEntityIdAndDisplayName() throws Exception {
        MyEntity origE = new MyEntityImpl(MutableMap.of("displayName", "mydisplayname"), origApp);
        String eId = origE.getId();
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        assertEquals(newE.getId(), eId);
        assertEquals(newE.getDisplayName(), "mydisplayname");
    }
    
    @Test
    public void testCanCustomizeRebind() throws Exception {
        MyEntity2 origE = new MyEntity2Impl(MutableMap.of("myfield", "myval"), origApp);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        
        MyEntity2 newE = (MyEntity2) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity2.class));
        assertEquals(newE.getMyfield(), "myval");
    }
    
    @Test
    public void testRebindsSubscriptions() throws Exception {
        MyEntity2 origE = new MyEntity2Impl(MutableMap.of("subscribe", true), origApp);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        MyEntity2 newE = (MyEntity2) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity2.class));
        
        newApp.setAttribute(MyApplication.MY_SENSOR, "mysensorval");
        TestUtils.assertEventually(Suppliers.ofInstance(newE.getEvents()), Predicates.equalTo(ImmutableList.of("mysensorval")));
    }
    
    @Test
    public void testHandlesReferencingOtherEntities() throws Exception {
        MyEntity origOtherE = new MyEntityImpl(origApp);
        MyEntityReffingOthers origE = new MyEntityReffingOthersImpl(
                MutableMap.builder()
                        .put("entityRef", origOtherE)
                        .build(),
                origApp);
        origE.setAttribute(MyEntityReffingOthers.ENTITY_REF_SENSOR, origOtherE);
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        MyEntityReffingOthers newE = (MyEntityReffingOthers) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntityReffingOthers.class));
        MyEntity newOtherE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        
        assertAttributeEquals(newE, MyEntityReffingOthers.ENTITY_REF_SENSOR, newOtherE);
        assertConfigEquals(newE, MyEntityReffingOthers.ENTITY_REF_CONFIG, newOtherE);
    }
    
    @Test
    public void testHandlesReferencingOtherLocations() throws Exception {
        MyLocation origLoc = new MyLocation();
        MyEntityReffingOthers origE = new MyEntityReffingOthersImpl(
                MutableMap.builder()
                        .put("locationRef", origLoc)
                        .build(),
                origApp);
        origE.setAttribute(MyEntityReffingOthers.LOCATION_REF_SENSOR, origLoc);
        Entities.startManagement(origApp, managementContext);
        origApp.start(ImmutableList.of(origLoc));
        
        MyApplication newApp = rebind();
        MyEntityReffingOthers newE = (MyEntityReffingOthers) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntityReffingOthers.class));
        MyLocation newLoc = (MyLocation) Iterables.getOnlyElement(newApp.getLocations());
        
        assertAttributeEquals(newE, MyEntityReffingOthers.LOCATION_REF_SENSOR, newLoc);
        assertConfigEquals(newE, MyEntityReffingOthers.LOCATION_REF_CONFIG, newLoc);
    }

    @Test
    public void testEntityManagementLifecycleAndVisibilityDuringRebind() throws Exception {
        MyLatchingEntityImpl.latching = false;
        MyLatchingEntity origE = new MyLatchingEntityImpl(origApp);
        Entities.startManagement(origApp, managementContext);
        MyLatchingEntityImpl.reset(); // after origE has been managed
        MyLatchingEntityImpl.latching = true;
        
        // Serialize and rebind, but don't yet manage the app
        RebindTestUtils.waitForPersisted(origApp);
        RebindTestUtils.checkCurrentMementoSerializable(origApp);
        final LocalManagementContext newManagementContext = new LocalManagementContext();
        Thread thread = new Thread() {
            public void run() {
                try {
                    RebindTestUtils.rebind(newManagementContext, mementoDir, getClass().getClassLoader());
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
            }
        };
        try {
            thread.start();
            
            assertTrue(MyLatchingEntityImpl.reconstructStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertNull(newManagementContext.getEntityManager().getEntity(origApp.getId()));
            assertNull(newManagementContext.getEntityManager().getEntity(origE.getId()));
            assertTrue(MyLatchingEntityImpl.managingStartedLatch.getCount() > 0);
            
            MyLatchingEntityImpl.reconstructContinuesLatch.countDown();
            assertTrue(MyLatchingEntityImpl.managingStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertNotNull(newManagementContext.getEntityManager().getEntity(origApp.getId()));
            assertNull(newManagementContext.getEntityManager().getEntity(origE.getId()));
            assertTrue(MyLatchingEntityImpl.managedStartedLatch.getCount() > 0);
            
            MyLatchingEntityImpl.managingContinuesLatch.countDown();
            assertTrue(MyLatchingEntityImpl.managedStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            assertNotNull(newManagementContext.getEntityManager().getEntity(origApp.getId()));
            assertNotNull(newManagementContext.getEntityManager().getEntity(origE.getId()));
            MyLatchingEntityImpl.managedContinuesLatch.countDown();

            thread.join(TIMEOUT_MS);
            assertFalse(thread.isAlive());
            
        } finally {
            thread.interrupt();
            MyLatchingEntityImpl.reset();
        }
    }
    
    @Test(groups="Integration") // takes more than 4 seconds, due to assertContinually calls
    public void testSubscriptionAndPublishingOnlyActiveWhenEntityIsManaged() throws Exception {
        MyLatchingEntityImpl.latching = false;
        MyLatchingEntity origE = new MyLatchingEntityImpl(MutableMap.of("subscribe", MyApplication.MY_SENSOR, "publish", "myvaltopublish"), origApp);
        Entities.startManagement(origApp, managementContext);
        MyLatchingEntityImpl.reset(); // after origE has been managed
        MyLatchingEntityImpl.latching = true;

        // Serialize and rebind, but don't yet manage the app
        RebindTestUtils.waitForPersisted(origApp);
        RebindTestUtils.checkCurrentMementoSerializable(origApp);
        final LocalManagementContext newManagementContext = new LocalManagementContext();
        Thread thread = new Thread() {
            public void run() {
                try {
                    RebindTestUtils.rebind(newManagementContext, mementoDir, getClass().getClassLoader());
                } catch (Exception e) {
                    throw Throwables.propagate(e);
                }
            }
        };
        try {
            thread.start();
            final List<Object> events = new CopyOnWriteArrayList<Object>();
            
            newManagementContext.getSubscriptionManager().subscribe(null, MyLatchingEntityImpl.MY_SENSOR, new SensorEventListener<Object>() {
                @Override public void onEvent(SensorEvent<Object> event) {
                    events.add(event.getValue());
                }});

            // In entity's reconstruct, publishes events are queued, and subscriptions don't yet take effect
            assertTrue(MyLatchingEntityImpl.reconstructStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            newManagementContext.getSubscriptionManager().publish(new BasicSensorEvent<String>(MyApplication.MY_SENSOR, null, "myvaltooearly"));
            
            TestUtils.assertContinuallyFromJava(Suppliers.ofInstance(MyLatchingEntityImpl.events), Predicates.equalTo(Collections.emptyList()));
            TestUtils.assertContinuallyFromJava(Suppliers.ofInstance(events), Predicates.equalTo(Collections.emptyList()));
            

            // When the entity is notified of "managing", then subscriptions take effect (but missed events not delivered); 
            // published events remain queued
            MyLatchingEntityImpl.reconstructContinuesLatch.countDown();
            assertTrue(MyLatchingEntityImpl.managingStartedLatch.getCount() > 0);

            TestUtils.assertContinuallyFromJava(Suppliers.ofInstance(events), Predicates.equalTo(Collections.emptyList()));
            TestUtils.assertContinuallyFromJava(Suppliers.ofInstance(MyLatchingEntityImpl.events), Predicates.equalTo(Collections.emptyList()));

            newManagementContext.getSubscriptionManager().publish(new BasicSensorEvent<String>(MyApplication.MY_SENSOR, null, "myvaltoreceive"));
            TestUtils.assertEventually(Suppliers.ofInstance(MyLatchingEntityImpl.events), Predicates.equalTo(ImmutableList.of("myvaltoreceive")));

            // When the entity is notified of "managed", its events are only then delivered
            MyLatchingEntityImpl.managingContinuesLatch.countDown();
            assertTrue(MyLatchingEntityImpl.managedStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

            TestUtils.assertEventually(Suppliers.ofInstance(MyLatchingEntityImpl.events), Predicates.equalTo(ImmutableList.of("myvaltoreceive")));
            
            MyLatchingEntityImpl.managedContinuesLatch.countDown();
            
            thread.join(TIMEOUT_MS);
            assertFalse(thread.isAlive());
            
        } finally {
            thread.interrupt();
            MyLatchingEntityImpl.reset();
        }

    }
    
    @Test
    public void testRestoresConfigKeys() throws Exception {
        TestEntity origE = new TestEntityImpl(origApp);
        origE.setConfig(TestEntity.CONF_LIST_PLAIN, ImmutableList.of("val1", "val2"));
        origE.setConfig(TestEntity.CONF_MAP_PLAIN, ImmutableMap.of("akey", "aval"));
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        final TestEntity newE = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        assertEquals(newE.getConfig(TestEntity.CONF_LIST_PLAIN), ImmutableList.of("val1", "val2"));
        assertEquals(newE.getConfig(TestEntity.CONF_MAP_PLAIN), ImmutableMap.of("akey", "aval"));
    }

    
    @Test
    public void testRestoresListConfigKey() throws Exception {
        TestEntity origE = new TestEntityImpl(origApp);
        origE.setConfig(TestEntity.CONF_LIST_THING.subKey(), "val1");
        origE.setConfig(TestEntity.CONF_LIST_THING.subKey(), "val2");
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        final TestEntity newE = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        assertEquals(newE.getConfig(TestEntity.CONF_LIST_THING), ImmutableList.of("val1", "val2"));
    }

    @Test
    public void testRestoresMapConfigKey() throws Exception {
        TestEntity origE = new TestEntityImpl(origApp);
        origE.setConfig(TestEntity.CONF_MAP_THING.subKey("akey"), "aval");
        origE.setConfig(TestEntity.CONF_MAP_THING.subKey("bkey"), "bval");
        Entities.startManagement(origApp, managementContext);
        
        MyApplication newApp = rebind();
        final TestEntity newE = (TestEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(TestEntity.class));

        assertEquals(newE.getConfig(TestEntity.CONF_MAP_THING), ImmutableMap.of("akey", "aval", "bkey", "bval"));
    }

    @Test
    public void testRebindPreservesInheritedConfig() throws Exception {
        MyEntity origE = new MyEntityImpl(origApp);
        origApp.setConfig(MyEntity.MY_CONFIG, "myValInSuper");
        Entities.startManagement(origApp, managementContext);

        // rebind: inherited config is preserved
        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        
        assertEquals(newE.getConfig(MyEntity.MY_CONFIG), "myValInSuper");
        assertEquals(newApp.getConfig(MyEntity.MY_CONFIG), "myValInSuper");
        
        // This config should be inherited by dynamically-added children of app
        MyEntity newE2 = new MyEntityImpl(origApp);
        Entities.manage(newE2);
        
        assertEquals(newE2.getConfig(MyEntity.MY_CONFIG), "myValInSuper");
        
    }

    @Test
    public void testRebindPreservesGetConfigWithDefault() throws Exception {
        MyEntity origE = new MyEntityImpl(origApp);
        Entities.startManagement(origApp, managementContext);

        assertNull(origE.getConfig(MyEntity.MY_CONFIG));
        assertEquals(origE.getConfig(MyEntity.MY_CONFIG, "mydefault"), "mydefault");
        
        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        
        assertNull(newE.getConfig(MyEntity.MY_CONFIG));
        assertEquals(newE.getConfig(MyEntity.MY_CONFIG, "mydefault"), "mydefault");
    }

    @Test
    public void testRebindPersistsNullAttribute() throws Exception {
        MyEntity origE = new MyEntityImpl(origApp);
        origE.setAttribute(MyEntity.MY_SENSOR, null);
        Entities.startManagement(origApp, managementContext);

        assertNull(origE.getAttribute(MyEntity.MY_SENSOR));

        MyApplication newApp = rebind();
        MyEntity newE = (MyEntity) Iterables.find(newApp.getChildren(), Predicates.instanceOf(MyEntity.class));
        
        assertNull(newE.getAttribute(MyEntity.MY_SENSOR));
    }

    private MyApplication rebind() throws Exception {
        RebindTestUtils.waitForPersisted(origApp);
        RebindTestUtils.checkCurrentMementoSerializable(origApp);
        return (MyApplication) RebindTestUtils.rebind(mementoDir, getClass().getClassLoader());
    }

    // TODO Don't want to extend EntityLocal, but tests want to call app.setAttribute
    @ImplementedBy(MyApplicationImpl.class)
    public interface MyApplication extends Application, Startable, EntityLocal {
        public static final AttributeSensor<String> MY_SENSOR = new BasicAttributeSensor<String>(
                        String.class, "test.app.mysensor", "My test sensor");
    }
    
    public static class MyApplicationImpl extends AbstractApplication implements MyApplication {
        private static final long serialVersionUID = 1L;
        
        public MyApplicationImpl() {
            super();
        }

        public MyApplicationImpl(Map flags) {
            super(flags);
        }
    }

    // TODO Don't want to extend EntityLocal, but tests want to call app.setAttribute
    @ImplementedBy(MyEntityImpl.class)
    public interface MyEntity extends Entity, Startable, EntityLocal {
        @SetFromFlag("myconfig")
        public static final ConfigKey<String> MY_CONFIG = new BasicConfigKey<String>(
                        String.class, "test.myentity.myconfig", "My test config");

        public static final AttributeSensor<String> MY_SENSOR = new BasicAttributeSensor<String>(
                String.class, "test.myentity.mysensor", "My test sensor");
    }
    
    public static class MyEntityImpl extends AbstractEntity implements MyEntity {
        private static final long serialVersionUID = 1L;
        
        private final Object dummy = new Object(); // so not serializable

        public MyEntityImpl() {
            super();
        }

        public MyEntityImpl(Entity parent) {
            super(parent);
        }
        
        public MyEntityImpl(Map flags, Entity parent) {
            super(flags, parent);
        }

        @Override
        public void start(Collection<? extends Location> locations) {
            getLocations().addAll(locations);
        }

        @Override
        public void stop() {
        }

        @Override
        public void restart() {
        }
    }

    // TODO Don't want to extend EntityLocal, but tests want to call app.setAttribute
    @ImplementedBy(MyEntityReffingOthersImpl.class)
    public interface MyEntityReffingOthers extends Entity, EntityLocal {
        @SetFromFlag("entityRef")
        public static final ConfigKey<Entity> ENTITY_REF_CONFIG = new BasicConfigKey<Entity>(
                        Entity.class, "test.config.entityref", "Ref to other entity");

        @SetFromFlag("locationRef")
        public static final ConfigKey<Location> LOCATION_REF_CONFIG = new BasicConfigKey<Location>(
                Location.class, "test.config.locationref", "Ref to other location");
        
        public static final AttributeSensor<Entity> ENTITY_REF_SENSOR = new BasicAttributeSensor<Entity>(
                Entity.class, "test.attribute.entityref", "Ref to other entity");
        
        public static final AttributeSensor<Location> LOCATION_REF_SENSOR = new BasicAttributeSensor<Location>(
                Location.class, "test.attribute.locationref", "Ref to other location");
    }
    
    public static class MyEntityReffingOthersImpl extends AbstractEntity implements MyEntityReffingOthers {
        private static final long serialVersionUID = 1L;
        
        @SetFromFlag("entityRef")
        public static final ConfigKey<Entity> ENTITY_REF_CONFIG = new BasicConfigKey<Entity>(
                        Entity.class, "test.config.entityref", "Ref to other entity");

        @SetFromFlag("locationRef")
        public static final ConfigKey<Location> LOCATION_REF_CONFIG = new BasicConfigKey<Location>(
                Location.class, "test.config.locationref", "Ref to other location");
        
        public static final AttributeSensor<Entity> ENTITY_REF_SENSOR = new BasicAttributeSensor<Entity>(
                Entity.class, "test.attribute.entityref", "Ref to other entity");
        
        public static final AttributeSensor<Location> LOCATION_REF_SENSOR = new BasicAttributeSensor<Location>(
                Location.class, "test.attribute.locationref", "Ref to other location");
        
        private final Object dummy = new Object(); // so not serializable

        public MyEntityReffingOthersImpl(Entity parent) {
            super(parent);
        }
        
        public MyEntityReffingOthersImpl(Map flags, Entity parent) {
            super(flags, parent);
        }
    }

    @ImplementedBy(MyEntity2Impl.class)
    public interface MyEntity2 extends Entity {
        @SetFromFlag("myconfig")
        public static final ConfigKey<String> MY_CONFIG = new BasicConfigKey<String>(
                        String.class, "test.myconfig", "My test config");

        @SetFromFlag("subscribe")
        public static final ConfigKey<Boolean> SUBSCRIBE = new BasicConfigKey<Boolean>(
                Boolean.class, "test.subscribe", "Whether to do some subscriptions on re-bind", false);
        
        public List<String> getEvents();
        
        public String getMyfield();
    }
    
    public static class MyEntity2Impl extends AbstractEntity implements MyEntity2 {
        private static final long serialVersionUID = 1L;
        
        @SetFromFlag
        String myfield;
        
        final List<String> events = new CopyOnWriteArrayList<String>();

        private final Object dummy = new Object(); // so not serializable

        public MyEntity2Impl() {
            super();
        }

        public MyEntity2Impl(Map flags, Entity parent) {
            super(flags, parent);
        }

        public List<String> getEvents() {
            return events;
        }

        public String getMyfield() {
            return myfield;
        }
        
        @Override
        public void onManagementStarting() {
            if (getConfig(SUBSCRIBE)) {
                subscribe(getApplication(), MyApplication.MY_SENSOR, new SensorEventListener<String>() {
                    @Override public void onEvent(SensorEvent<String> event) {
                        events.add(event.getValue());
                    }
                });
            }
        }
        
        @Override
        public RebindSupport<EntityMemento> getRebindSupport() {
            return new BasicEntityRebindSupport(this) {
                @Override public EntityMemento getMemento() {
                    // Note: using MutableMap so accepts nulls
                    return getMementoWithProperties(MutableMap.<String,Object>of("myfield", myfield));
                }
                @Override protected void doReconstruct(RebindContext rebindContext, EntityMemento memento) {
                    super.doReconstruct(rebindContext, memento);
                    myfield = (String) memento.getCustomField("myfield");
                }
            };
        }
    }

    @ImplementedBy(MyLatchingEntityImpl.class)
    public interface MyLatchingEntity extends Entity {
        @SetFromFlag("subscribe")
        public static final ConfigKey<AttributeSensor<?>> SUBSCRIBE = new BasicConfigKey(
                AttributeSensor.class, "test.mylatchingentity.subscribe", "Sensor to subscribe to (or null means don't)", null);
        
        @SetFromFlag("publish")
        public static final ConfigKey<String> PUBLISH = new BasicConfigKey<String>(
                String.class, "test.mylatchingentity.publish", "Value to publish (or null means don't)", null);

        public static final AttributeSensor<String> MY_SENSOR = new BasicAttributeSensor<String>(
                String.class, "test.mylatchingentity.mysensor", "My test sensor");
    }
    
    public static class MyLatchingEntityImpl extends AbstractEntity implements MyLatchingEntity {
        private static final long serialVersionUID = 1L;
        static volatile CountDownLatch reconstructStartedLatch;
        static volatile CountDownLatch reconstructContinuesLatch;
        static volatile CountDownLatch managingStartedLatch;
        static volatile CountDownLatch managingContinuesLatch;
        static volatile CountDownLatch managedStartedLatch;
        static volatile CountDownLatch managedContinuesLatch;

        static volatile boolean latching = false;
        static volatile List<Object> events;

        static void reset() {
            latching = false;
            events = new CopyOnWriteArrayList<Object>();

            reconstructStartedLatch = new CountDownLatch(1);
            reconstructContinuesLatch = new CountDownLatch(1);
            managingStartedLatch = new CountDownLatch(1);
            managingContinuesLatch = new CountDownLatch(1);
            managedStartedLatch = new CountDownLatch(1);
            managedContinuesLatch = new CountDownLatch(1);
        }

        public MyLatchingEntityImpl() {
            super();
        }

        public MyLatchingEntityImpl(Entity parent) {
            super(parent);
        }
        
        public MyLatchingEntityImpl(Map flags, Entity parent) {
            super(flags, parent);
        }

        private void onReconstruct() {
            if (getConfig(SUBSCRIBE) != null) {
                getManagementSupport().getSubscriptionContext().subscribe(null, getConfig(SUBSCRIBE), new SensorEventListener<Object>() {
                        @Override public void onEvent(SensorEvent<Object> event) {
                            events.add(event.getValue());
                        }});
            }

            if (getConfig(PUBLISH) != null) {
                setAttribute(MY_SENSOR, getConfig(PUBLISH));
            }

            if (latching) {
                reconstructStartedLatch.countDown();
                try {
                    reconstructContinuesLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeInterruptedException(e);
                }
            }
        }
        
        @Override
        public void onManagementStarting() {
            if (latching) {
                managingStartedLatch.countDown();
                try {
                    managingContinuesLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeInterruptedException(e);
                }
            }
        }
        
        @Override
        public void onManagementStarted() {
            if (latching) {
                managedStartedLatch.countDown();
                try {
                    managedContinuesLatch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeInterruptedException(e);
                }
            }
        }
        
        @Override
        public RebindSupport<EntityMemento> getRebindSupport() {
            return new BasicEntityRebindSupport(this) {
                @Override protected void doReconstruct(RebindContext rebindContext, EntityMemento memento) {
                    MyLatchingEntityImpl.this.onReconstruct();
                }
            };
        }
    }
}
