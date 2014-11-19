package com.sequenceiq.periscope.service.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.client.CloudbreakClient;
import com.sequenceiq.periscope.domain.Ambari;
import com.sequenceiq.periscope.domain.PeriscopeUser;
import com.sequenceiq.periscope.service.CloudbreakService;

import groovyx.net.http.HttpResponseException;

@Service
public class ClusterSecurityService {

    @Autowired
    private CloudbreakService cloudbreakService;

    public boolean hasAccess(PeriscopeUser user, Ambari ambari) {
        CloudbreakClient client = cloudbreakService.getClient();
        try {
            return client.hasAccess(user.getId(), user.getAccount(), ambari.getHost());
        } catch (HttpResponseException e) {
            // if the cluster is unknown for cloudbreak
            // it should allow it to monitor
            return true;
        }
    }

    public Ambari tryResolve(Ambari ambari) {
        CloudbreakClient client = cloudbreakService.getClient();
        try {
            String host = ambari.getHost();
            String user = ambari.getUser();
            String pass = ambari.getPass();
            if (user == null && pass == null) {
                int id = client.resolveToStackId(host);
                Map<String, String> stack = (Map<String, String>) client.getStack("" + id);
                return new Ambari(host, ambari.getPort(), stack.get("userName"), stack.get("password"));
            } else {
                return ambari;
            }
        } catch (Exception e) {
            return ambari;
        }
    }

}
