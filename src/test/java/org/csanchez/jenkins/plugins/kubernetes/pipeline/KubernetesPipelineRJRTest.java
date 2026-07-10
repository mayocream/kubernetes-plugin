package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.AssertBuildLogMessage;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.AssertBuildStatusSuccess;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.AssertNoOrphanedPods;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.CreateWorkflowJobThenScheduleTask;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.RunId;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.steps.SetupCloud;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;

class KubernetesPipelineRJRTest extends AbstractKubernetesPipelineRJRTest {

    public KubernetesPipelineRJRTest() throws Exception {
        super(new SetupCloud());
    }

    @Test
    void basicPipeline() throws Throwable {
        RunId runId = createWorkflowJobThenScheduleRun();
        rjr.runRemotely(new AssertBuildStatusSuccess(runId));
    }

    @Test
    void restartDuringPodLaunch() throws Throwable {
        // try to run something on a pod which is not schedulable (disktype=special)
        RunId build = rjr.runRemotely(new CreateWorkflowJobThenScheduleTask(
                KubernetesTestUtil.loadPipelineScript(getClass(), name + ".groovy")));
        // the pod is created, but not connected yet
        rjr.runRemotely(new AssertBuildLogMessage("Created Pod", build));
        // restart
        rjr.stopJenkins();
        rjr.startJenkins();
        try {
            // update k8s to make a node suitable to schedule (add disktype=special to the node)
            addNodeLabel("disktype", "special");
            // pod connects back and the build finishes correctly
            rjr.runRemotely(new AssertBuildStatusSuccess(build));
        } finally {
            removeNodeLabel("disktype");
        }
    }

    /**
     * Restart while a dynamic-pod-template agent is being provisioned (pod created, not yet connected). On resume,
     * the launcher must wait for the dynamic template to be re-registered rather than failing and leaking the pod.
     * Verifies that the build completes on the provisioned agent and that no pod is orphaned. See <a href="https://github.com/jenkinsci/kubernetes-plugin/issues/2512">...</a>.
     */
    @Issue("JENKINS-67390")
    @Test
    public void restartDuringPodLaunchDoesNotLeakPod() throws Throwable {
        // A pod that cannot be scheduled yet (disktype=special), so it stays pending while we restart
        RunId build = rjr.runRemotely(new CreateWorkflowJobThenScheduleTask(
                KubernetesTestUtil.loadPipelineScript(getClass(), name.getMethodName() + ".groovy")));
        rjr.runRemotely(new AssertBuildLogMessage("Created Pod", build));
        // Restart while the pod exists but has not connected and the dynamic template is not yet re-registered.
        rjr.stopJenkins();
        rjr.startJenkins();
        try {
            addNodeLabel("disktype", "special");
            // With the fix, the launcher waits for the template, the agent connects, and the build finishes
            rjr.runRemotely(new AssertBuildStatusSuccess(build));
            // the pod created before the restart must not be left orphaned
            rjr.runRemotely(new AssertNoOrphanedPods());
        } finally {
            removeNodeLabel("disktype");
        }
    }

    private static void addNodeLabel(String key, String value) {
        editNode(builder -> builder.editMetadata().addToLabels(key, value).endMetadata());
    }

    private static void removeNodeLabel(String key) {
        editNode(builder -> builder.editMetadata().removeFromLabels(key).endMetadata());
    }

    private static void editNode(java.util.function.UnaryOperator<NodeBuilder> edit) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            String nodeName =
                    client.nodes().list().getItems().get(0).getMetadata().getName();
            client.nodes()
                    .withName(nodeName)
                    .edit(n -> edit.apply(new NodeBuilder(n)).build());
        }
    }
}
