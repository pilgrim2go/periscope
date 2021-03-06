package com.sequenceiq.periscope.monitor.request;

import org.apache.hadoop.yarn.server.resourcemanager.webapp.dao.ClusterMetricsInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;

import com.sequenceiq.periscope.domain.Cluster;
import com.sequenceiq.periscope.log.Logger;
import com.sequenceiq.periscope.log.PeriscopeLoggerFactory;
import com.sequenceiq.periscope.monitor.event.ClusterMetricsUpdateEvent;
import com.sequenceiq.periscope.monitor.event.UpdateFailedEvent;
import com.sequenceiq.periscope.service.configuration.ConfigParam;

@Component("ClusterMetricsRequest")
@Scope("prototype")
public class ClusterMetricsRequest extends AbstractEventPublisher implements Runnable, EventPublisher {

    private static final Logger LOGGER = PeriscopeLoggerFactory.getLogger(ClusterMetricsRequest.class);
    private static final String WS_URL = "/ws/v1/cluster/metrics";

    @Autowired
    private RestOperations restOperations;
    private final Cluster cluster;

    public ClusterMetricsRequest(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public void run() {
        try {
            String rmAddress = cluster.getConfigValue(ConfigParam.YARN_RM_WEB_ADDRESS, "");
            String url = "http://" + rmAddress + WS_URL;
            ClusterMetricsInfo response = restOperations.getForObject(url, ClusterMetricsInfo.class);
            publishEvent(new ClusterMetricsUpdateEvent(response, cluster.getId()));
        } catch (Exception e) {
            LOGGER.error(cluster.getId(), "Error updating the cluster metrics via WebService", e);
            publishEvent(new UpdateFailedEvent(cluster.getId()));
        }
    }
}
