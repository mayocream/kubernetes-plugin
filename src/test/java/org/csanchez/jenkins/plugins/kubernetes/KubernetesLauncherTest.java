package org.csanchez.jenkins.plugins.kubernetes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.jvnet.hudson.test.WithoutJenkins;

public class KubernetesLauncherTest {

    private static final String K8S_RESOURCE_QUOTA_CONFLICT =
            "Operation cannot be fulfilled on resourcequotas \"compute-resources\": the object has been modified; please apply your changes to the latest version and try again";

    private static final String OPENSHIFT_CLUSTER_RESOURCE_QUOTA_CONFLICT =
            "Operation cannot be fulfilled on clusterresourcequotas.quota.openshift.io \"dno--notterminating\": the object has been modified; please apply your changes to the latest version and try again";

    @WithoutJenkins
    @Test
    public void isResourceQuotaUpdateConflict_kubernetesResourceQuota() {
        assertTrue(KubernetesLauncher.isResourceQuotaUpdateConflict(409, K8S_RESOURCE_QUOTA_CONFLICT));
    }

    @WithoutJenkins
    @Test
    public void isResourceQuotaUpdateConflict_openshiftClusterResourceQuota() {
        assertTrue(KubernetesLauncher.isResourceQuotaUpdateConflict(409, OPENSHIFT_CLUSTER_RESOURCE_QUOTA_CONFLICT));
    }

    @WithoutJenkins
    @Test
    public void isResourceQuotaUpdateConflict_not409() {
        assertFalse(KubernetesLauncher.isResourceQuotaUpdateConflict(403, K8S_RESOURCE_QUOTA_CONFLICT));
    }

    @WithoutJenkins
    @Test
    public void isResourceQuotaUpdateConflict_nullMessage() {
        assertFalse(KubernetesLauncher.isResourceQuotaUpdateConflict(409, null));
    }

    @WithoutJenkins
    @Test
    public void isResourceQuotaUpdateConflict_unrelated409() {
        assertFalse(
                KubernetesLauncher.isResourceQuotaUpdateConflict(409, "pods \"my-pod\" already exists"));
    }
}
