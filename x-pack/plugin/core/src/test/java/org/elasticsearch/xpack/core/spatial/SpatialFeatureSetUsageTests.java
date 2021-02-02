/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.spatial;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.test.AbstractWireSerializingTestCase;
import org.elasticsearch.xpack.core.common.stats.EnumCounters;
import org.elasticsearch.xpack.core.spatial.action.SpatialStatsAction;

import java.io.IOException;
import java.net.InetAddress;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class SpatialFeatureSetUsageTests extends AbstractWireSerializingTestCase<SpatialFeatureSetUsage> {

    @Override
    protected SpatialFeatureSetUsage createTestInstance() {
        boolean available = randomBoolean();
        boolean enabled = randomBoolean();
        SpatialStatsAction.Response statsResponse = randomStatsResponse();
        return new SpatialFeatureSetUsage(available, enabled, statsResponse);
    }

    @Override
    protected SpatialFeatureSetUsage mutateInstance(SpatialFeatureSetUsage instance) throws IOException {
        boolean available = instance.available();
        boolean enabled = instance.enabled();
        SpatialStatsAction.Response statsResponse = instance.statsResponse();
        switch (between(0, 1)) {
            case 0:
                available = available == false;
                break;
            case 1:
                enabled = enabled == false;
                break;
            default:
                throw new AssertionError("Illegal randomisation branch");
        }
        return new SpatialFeatureSetUsage(available, enabled, statsResponse);
    }

    @Override
    protected Writeable.Reader<SpatialFeatureSetUsage> instanceReader() {
        return SpatialFeatureSetUsage::new;
    }

   public static SpatialStatsAction.Response randomStatsResponse() {
        DiscoveryNode node = new DiscoveryNode("_node_id",
            new TransportAddress(InetAddress.getLoopbackAddress(), 9300), Version.CURRENT);
        EnumCounters<SpatialStatsAction.Item> counters = new EnumCounters<>(SpatialStatsAction.Item.class);
        SpatialStatsAction.NodeResponse nodeResponse = new SpatialStatsAction.NodeResponse(node, counters);
        return new SpatialStatsAction.Response(new ClusterName("cluster_name"), singletonList(nodeResponse), emptyList());
    }
}
