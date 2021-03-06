package com.sequenceiq.periscope.service;

import static java.util.Arrays.asList;
import static org.springframework.util.StringUtils.arrayToCommaDelimitedString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sequenceiq.ambari.client.AmbariClient;
import com.sequenceiq.periscope.domain.Cluster;
import com.sequenceiq.periscope.domain.PeriscopeUser;
import com.sequenceiq.periscope.log.Logger;
import com.sequenceiq.periscope.log.PeriscopeLoggerFactory;
import com.sequenceiq.periscope.model.AmbariStack;
import com.sequenceiq.periscope.model.HostResolution;
import com.sequenceiq.periscope.model.Queue;
import com.sequenceiq.periscope.model.QueueSetup;
import com.sequenceiq.periscope.registry.ClusterRegistry;
import com.sequenceiq.periscope.registry.ClusterState;
import com.sequenceiq.periscope.registry.ConnectionException;
import com.sequenceiq.periscope.registry.QueueSetupException;
import com.sequenceiq.periscope.repository.ClusterRepository;
import com.sequenceiq.periscope.repository.MetricAlarmRepository;
import com.sequenceiq.periscope.repository.TimeAlarmRepository;
import com.sequenceiq.periscope.repository.UserRepository;
import com.sequenceiq.periscope.rest.json.ScalingConfigurationJson;
import com.sequenceiq.periscope.utils.ClusterUtils;

@Service
public class ClusterService {

    private static final Logger LOGGER = PeriscopeLoggerFactory.getLogger(ClusterService.class);
    private static final String CAPACITY_SCHEDULER = "capacity-scheduler";
    private static final String ROOT_PREFIX = "yarn.scheduler.capacity.root.";
    private static final String QUEUE_NAMES = ROOT_PREFIX + "queues";
    private static final String DEFAULT_QUEUE_NAME = "default";

    @Autowired
    private ClusterRegistry clusterRegistry;
    @Autowired
    private ClusterRepository clusterRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MetricAlarmRepository metricAlarmRepository;
    @Autowired
    private TimeAlarmRepository timeAlarmRepository;

    @Value("${periscope.hostname.resolution:private}")
    private String hostNameResolution;

    public Cluster add(PeriscopeUser user, AmbariStack stack) throws ConnectionException {
        PeriscopeUser periscopeUser = userRepository.findOne(user.getId());
        if (periscopeUser == null) {
            periscopeUser = userRepository.save(user);
        }
        Cluster cluster = new Cluster(periscopeUser, stack, HostResolution.valueOf(hostNameResolution.toUpperCase()));
        cluster.start();
        clusterRepository.save(cluster);
        return clusterRegistry.add(user, cluster);
    }

    public Cluster modify(PeriscopeUser user, long clusterId, AmbariStack stack) throws ClusterNotFoundException, ConnectionException {
        Cluster cluster = get(user, clusterId);
        try {
            Cluster probe = new Cluster(user, stack, HostResolution.valueOf(hostNameResolution.toUpperCase()));
            probe.start();
            probe.stop();
        } catch (ConnectionException e) {
            LOGGER.warn(clusterId, "Cannot modify the cluster as it fails to connect to Ambari", e);
            throw e;
        }
        cluster.setAmbari(stack.getAmbari());
        cluster.setStackId(stack.getStackId());
        cluster.refreshConfiguration();
        clusterRepository.save(cluster);
        return cluster;
    }

    public Cluster get(PeriscopeUser user, long clusterId) throws ClusterNotFoundException {
        Cluster cluster = clusterRegistry.get(user, clusterId);
        if (cluster == null) {
            throw new ClusterNotFoundException(clusterId);
        }
        return cluster;
    }

    public Cluster get(long clusterId) throws ClusterNotFoundException {
        Cluster cluster = clusterRegistry.get(clusterId);
        if (cluster == null) {
            throw new ClusterNotFoundException(clusterId);
        }
        return cluster;
    }

    public List<Cluster> getAll(PeriscopeUser user) {
        return clusterRegistry.getAll(user);
    }

    public void remove(PeriscopeUser user, long clusterId) throws ClusterNotFoundException {
        Cluster cluster = clusterRegistry.remove(user, clusterId);
        if (cluster == null) {
            throw new ClusterNotFoundException(clusterId);
        }
        clusterRepository.delete(cluster);
    }

    public void setScalingConfiguration(PeriscopeUser user, long clusterId, ScalingConfigurationJson scalingConfiguration)
            throws ClusterNotFoundException {
        Cluster cluster = get(user, clusterId);
        cluster.setMinSize(scalingConfiguration.getMinSize());
        cluster.setMaxSize(scalingConfiguration.getMaxSize());
        cluster.setCoolDown(scalingConfiguration.getCoolDown());
        clusterRepository.save(cluster);
    }

    public ScalingConfigurationJson getScalingConfiguration(PeriscopeUser user, long clusterId) throws ClusterNotFoundException {
        Cluster cluster = get(user, clusterId);
        ScalingConfigurationJson configuration = new ScalingConfigurationJson();
        configuration.setCoolDown(cluster.getCoolDown());
        configuration.setMaxSize(cluster.getMaxSize());
        configuration.setMinSize(cluster.getMinSize());
        return configuration;
    }

    public Cluster setState(PeriscopeUser user, long clusterId, ClusterState state)
            throws ClusterNotFoundException, ConnectionException {
        Cluster cluster = get(user, clusterId);
        cluster.setState(state);
        if (ClusterState.RUNNING == state) {
            try {
                cluster.start();
            } catch (ConnectionException e) {
                cluster.setState(ClusterState.SUSPENDED);
                cluster.stop();
                throw e;
            }
        } else {
            cluster.stop();
        }
        clusterRepository.save(cluster);
        return cluster;
    }

    public Cluster refreshConfiguration(PeriscopeUser user, long clusterId) throws ConnectionException, ClusterNotFoundException {
        Cluster cluster = get(user, clusterId);
        cluster.refreshConfiguration();
        return cluster;
    }

    public Map<String, String> setQueueSetup(PeriscopeUser user, long clusterId, QueueSetup queueSetup)
            throws QueueSetupException, ClusterNotFoundException {
        return setQueueSetup(get(user, clusterId), queueSetup);
    }

    public Map<String, String> setQueueSetup(Cluster cluster, QueueSetup queueSetup) throws QueueSetupException {
        AmbariClient ambariClient = cluster.newAmbariClient();
        Map<String, String> csConfig = ambariClient.getServiceConfigMap().get(CAPACITY_SCHEDULER);
        validateCSConfig(csConfig, queueSetup);
        Map<String, String> newConfig = generateNewQueueConfig(csConfig, queueSetup);
        ambariClient.modifyConfiguration(CAPACITY_SCHEDULER, newConfig);
        // TODO https://issues.apache.org/jira/browse/AMBARI-5937
        ambariClient.restartServiceComponents("YARN", asList("NODEMANAGER", "RESOURCEMANAGER", "YARN_CLIENT"));
        cluster.setRestarting(true);
        return newConfig;
    }

    private void validateCSConfig(Map<String, String> csConfig, QueueSetup queueSetup) throws QueueSetupException {
        if (csConfig == null) {
            throwQueueSetupException("Capacity-scheduler config not found");
        }
        int capacity = 0;
        List<String> queueNames = new ArrayList<>(queueSetup.getSetup().size());
        for (Queue queue : queueSetup.getSetup()) {
            String name = queue.getName();
            if (queueNames.contains(name)) {
                throwQueueSetupException("Queue name: " + name + " specified twice");
            }
            capacity += queue.getCapacity();
            queueNames.add(name);
        }
        if (capacity != ClusterUtils.MAX_CAPACITY) {
            throwQueueSetupException("Global queue capacities must be 100");
        }
        if (!queueNames.contains(DEFAULT_QUEUE_NAME)) {
            throwQueueSetupException("Default queue must exist");
        }
    }

    private Map<String, String> generateNewQueueConfig(Map<String, String> csConfig, QueueSetup queueSetup) {
        Map<String, String> config = new HashMap<>();
        List<String> newQueueNames = new LinkedList<>();
        for (Queue queue : queueSetup.getSetup()) {
            String name = queue.getName();
            int capacity = queue.getCapacity();
            config.put(ROOT_PREFIX + name + ".acl_administer_jobs", "*");
            config.put(ROOT_PREFIX + name + ".acl_submit_applications", "*");
            config.put(ROOT_PREFIX + name + ".capacity", "" + capacity);
            config.put(ROOT_PREFIX + name + ".maximum-capacity", "" + capacity);
            config.put(ROOT_PREFIX + name + ".state", "RUNNING");
            config.put(ROOT_PREFIX + name + ".user-limit-factor", "1");
            newQueueNames.add(name);
        }
        copyNonQueueRelatedProperties(csConfig, config);
        config.put(QUEUE_NAMES, arrayToCommaDelimitedString(newQueueNames.toArray()));
        return config;
    }

    private void copyNonQueueRelatedProperties(Map<String, String> from, Map<String, String> to) {
        String[] queueNames = getQueueNames(from);
        for (String key : from.keySet()) {
            if (!isQueueProperty(key, queueNames)) {
                to.put(key, from.get(key));
            }
        }
    }

    private String[] getQueueNames(Map<String, String> csConfig) {
        return csConfig.get(QUEUE_NAMES).split(",");
    }

    private boolean isQueueProperty(String key, String[] queueNames) {
        boolean result = false;
        for (String name : queueNames) {
            if (key.startsWith(ROOT_PREFIX + name)) {
                result = true;
            }
        }
        return result;
    }

    private void throwQueueSetupException(String message) throws QueueSetupException {
        throw new QueueSetupException(message);
    }

}
