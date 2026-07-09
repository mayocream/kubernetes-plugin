package org.csanchez.jenkins.plugins.kubernetes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

class KubernetesLauncherTest {

    private static final String K8S_RESOURCE_QUOTA_CONFLICT =
            "Operation cannot be fulfilled on resourcequotas \"compute-resources\": the object has been modified; please apply your changes to the latest version and try again";

    private static final String OPENSHIFT_CLUSTER_RESOURCE_QUOTA_CONFLICT =
            "Operation cannot be fulfilled on clusterresourcequotas.quota.openshift.io \"dno--notterminating\": the object has been modified; please apply your changes to the latest version and try again";

    @Test
    void isResourceQuotaUpdateConflict_kubernetesResourceQuota() {
        assertThat(KubernetesLauncher.isResourceQuotaUpdateConflict(409, K8S_RESOURCE_QUOTA_CONFLICT), is(true));
    }

    @Test
    void isResourceQuotaUpdateConflict_openshiftClusterResourceQuota() {
        assertThat(
                KubernetesLauncher.isResourceQuotaUpdateConflict(409, OPENSHIFT_CLUSTER_RESOURCE_QUOTA_CONFLICT),
                is(true));
    }

    @Test
    void isResourceQuotaUpdateConflict_not409() {
        assertThat(KubernetesLauncher.isResourceQuotaUpdateConflict(403, K8S_RESOURCE_QUOTA_CONFLICT), is(false));
    }

    @Test
    void isResourceQuotaUpdateConflict_nullMessage() {
        assertThat(KubernetesLauncher.isResourceQuotaUpdateConflict(409, null), is(false));
    }

    @Test
    void isResourceQuotaUpdateConflict_unrelated409() {
        assertThat(KubernetesLauncher.isResourceQuotaUpdateConflict(409, "pods \"my-pod\" already exists"), is(false));
    }
}
