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
package brooklyn.demo;

import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.nosql.redis.RedisCluster;
import brooklyn.entity.proxying.EntitySpecs;

/** Redis cluster. */
public class SimpleRedisCluster extends ApplicationBuilder {

    /** Create entities. */
    protected void doBuild() {
        createChild(EntitySpecs.spec(RedisCluster.class)
                .configure("initialSize", "2")
                .configure("clusterName", "Brooklyn"));
    }

}
