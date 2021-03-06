/*
 * Copyright 2012-2013 by Cloudsoft Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.entity.nosql.couchdb;

import java.util.Map;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.trait.Startable;
import brooklyn.test.EntityTestUtils;
import brooklyn.util.MutableMap;
import brooklyn.util.text.Strings;

import com.google.common.collect.ImmutableList;

/**
 * CouchDB live tests.
 *
 * Test the operation of the {@link CouchDBNode} class using the jclouds {@code rackspace-cloudservers-uk}
 * and {@code aws-ec2} providers, with different OS images. The tests use the {@link JcouchdbSupport#jcouchdbTest(CouchDBNode)} method
 * to exercise the node, and will need to have {@code brooklyn.jclouds.provider.identity} and {@code .credential}
 * set, usually in the {@code .brooklyn/brooklyn.properties} file.
 */
public class CouchDBNodeLiveTest extends AbstractCouchDBNodeTest {

    @DataProvider(name = "virtualMachineData")
    public Object[][] provideVirtualMachineData() {
        return new Object[][] { // ImageName, Provider, Region
            new Object[] { "ubuntu", "aws-ec2", "eu-west-1" },
            new Object[] { "Ubuntu 12.0", "rackspace-cloudservers-uk", "" },
            new Object[] { "CentOS 6.2", "rackspace-cloudservers-uk", "" },
        };
    }

    @Test(groups = "Live", dataProvider = "virtualMachineData")
    protected void testOperatingSystemProvider(String imageName, String provider, String region) throws Exception {
        log.info("Testing CouchDB on {}{} using {}", new Object[] { provider, Strings.isNonEmpty(region) ? ":" + region : "", imageName });

        Map<String, String> properties = MutableMap.of("image-name-matches", imageName);
        testLocation = app.getManagementContext().getLocationRegistry()
                .resolve(provider + (Strings.isNonEmpty(region) ? ":" + region : ""), properties);

        couchdb = app.createAndManageChild(EntitySpecs.spec(CouchDBNode.class)
                .configure("httpPort", "12345+")
                .configure("clusterName", "TestCluster"));
        app.start(ImmutableList.of(testLocation));
        EntityTestUtils.assertAttributeEqualsEventually(couchdb, Startable.SERVICE_UP, true);

        JcouchdbSupport jcouchdb = new JcouchdbSupport(couchdb);
        jcouchdb.jcouchdbTest();
    }
}
