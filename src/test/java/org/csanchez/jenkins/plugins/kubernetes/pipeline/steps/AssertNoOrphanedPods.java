package org.csanchez.jenkins.plugins.kubernetes.pipeline.steps;

import static org.awaitility.Awaitility.await;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RealJenkinsRule;

/**
 * Asserts that there are no orphaned agent pods, i.e. no pod labelled {@code jenkins=slave} without a
 * corresponding {@link KubernetesSlave} node. Because a legitimately terminating pod may briefly remain after its node is gone,
 * this waits for any such pod to disappear. A genuinely orphaned pod never gets cleaned up,
 * so the wait times out and the assertion fails.
 */
public class AssertNoOrphanedPods implements RealJenkinsRule.Step {

    @Override
    public void run(JenkinsRule r) throws Throwable {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            await("agent pods with no corresponding Jenkins node (leaked)")
                    .atMost(60, TimeUnit.SECONDS)
                    .until(() -> orphanedPods(r, client).isEmpty());
        }
    }

    private static List<String> orphanedPods(JenkinsRule r, KubernetesClient client) {
        Set<String> knownPodNames = r.jenkins.getNodes().stream()
                .filter(KubernetesSlave.class::isInstance)
                .map(KubernetesSlave.class::cast)
                .map(KubernetesSlave::getPodName)
                .collect(Collectors.toSet());
        return client.pods().withLabel("jenkins", "slave").list().getItems().stream()
                .map(Pod::getMetadata)
                .map(ObjectMeta::getName)
                .filter(name -> !knownPodNames.contains(name))
                .collect(Collectors.toList());
    }
}
