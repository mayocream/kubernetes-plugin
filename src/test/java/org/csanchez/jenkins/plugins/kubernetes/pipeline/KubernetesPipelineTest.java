/*
 * The MIT License
 *
 * Copyright (c) 2016, Carlos Sanchez
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.awaitility.Awaitility.await;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.CONTAINER_ENV_VAR_FROM_SECRET_VALUE;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.POD_ENV_VAR_FROM_SECRET_VALUE;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.WINDOWS_1809_BUILD;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.assumeWindows;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.deletePods;
import static org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil.getLabels;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeNotNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Result;
import hudson.model.Run;
import hudson.slaves.SlaveComputer;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import org.csanchez.jenkins.plugins.kubernetes.GarbageCollection;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesComputer;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesTestUtil;
import org.csanchez.jenkins.plugins.kubernetes.MetricNames;
import org.csanchez.jenkins.plugins.kubernetes.PodAnnotation;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils;
import org.csanchez.jenkins.plugins.kubernetes.pod.decorator.PodDecorator;
import org.csanchez.jenkins.plugins.kubernetes.pod.decorator.PodDecoratorException;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.Reaper;
import org.hamcrest.MatcherAssert;
import org.htmlunit.html.DomNodeUtil;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.kubernetes.auth.KubernetesAuthException;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;

public class KubernetesPipelineTest extends AbstractKubernetesPipelineTest {

    private static final Logger LOGGER = Logger.getLogger(KubernetesPipelineTest.class.getName());
    public static final String POD_DEADLINE_EXCEEDED_MESSAGE =
            "Pod just failed. Reason: DeadlineExceeded, Message: Pod was active on the node longer than the specified deadline.";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Rule
    public LoggerRule warnings = new LoggerRule().quiet();

    @Rule
    public FlagRule<Boolean> substituteEnv =
            new FlagRule<>(() -> PodTemplateUtils.SUBSTITUTE_ENV, x -> PodTemplateUtils.SUBSTITUTE_ENV = x);

    @Before
    public void setUp() throws Exception {
        // Had some problems with FileChannel.close hangs from WorkflowRun.save:
        r.jenkins
                .getDescriptorByType(GlobalDefaultFlowDurabilityLevel.DescriptorImpl.class)
                .setDurabilityHint(FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
        deletePods(cloud.connect(), getLabels(cloud, this, name), false);
        assertNotNull(createJobThenScheduleRun());
    }

    /**
     * Ensure all builds are complete by the end of the test.
     */
    @After
    public void allDead() throws Exception {
        if (b != null && b.isLogUpdated()) {
            LOGGER.warning(() -> "Had to interrupt " + b);
            b.getExecutor().interrupt();
        }
        for (int i = 0; i < 100 && r.isSomethingHappening(); i++) {
            Thread.sleep(100);
        }
        Jenkins.get().getNodes().stream()
                .filter(KubernetesSlave.class::isInstance)
                .map(KubernetesSlave.class::cast)
                .forEach(agent -> {
                    LOGGER.info(() -> "Deleting remaining node " + agent);
                    try {
                        agent.terminate();
                    } catch (InterruptedException | IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to terminate " + agent, e);
                    }
                });
    }

    @Issue("JENKINS-57993")
    @Test
    public void runInPod() throws Exception {
        warnings.record("", Level.WARNING).capture(1000);
        SemaphoreStep.waitForStart("podTemplate/1", b);
        List<PodTemplate> templates = podTemplatesWithLabel(name.getMethodName(), cloud.getAllTemplates());
        assertThat(templates, hasSize(1));
        SemaphoreStep.success("podTemplate/1", null);

        // check if build failed
        assertTrue("Build has failed early: " + b.getResult(), b.isBuilding() || Result.SUCCESS.equals(b.getResult()));

        LOGGER.log(Level.INFO, "Found templates with label runInPod: {0}", templates);
        for (PodTemplate template : cloud.getAllTemplates()) {
            LOGGER.log(Level.INFO, "Cloud template \"{0}\" labels: {1}", new Object[] {
                template.getName(), template.getLabelSet()
            });
        }

        Map<String, String> labels = getLabels(cloud, this, name);
        SemaphoreStep.waitForStart("pod/1", b);
        for (Computer c : getKubernetesComputers()) { // TODO perhaps this should be built into JenkinsRule via
            // ComputerListener.preLaunch?
            new Thread(
                            () -> {
                                long pos = 0;
                                try {
                                    while (Jenkins.getInstanceOrNull()
                                            != null) { // otherwise get NPE from Computer.getLogDir
                                        if (c.getLogFile().isFile()) { // TODO should LargeText.FileSession handle this?
                                            pos = c.getLogText().writeLogTo(pos, System.out);
                                        }
                                        Thread.sleep(100);
                                    }
                                } catch (Exception x) {
                                    x.printStackTrace();
                                }
                            },
                            "watching logs for " + c.getDisplayName())
                    .start();
            System.out.println(c.getLog());
        }
        PodList pods = cloud.connect().pods().withLabels(labels).list();
        assertThat(
                "Expected one pod with labels " + labels + " but got: "
                        + pods.getItems().stream().map(pod -> pod.getMetadata()).collect(Collectors.toList()),
                pods.getItems(),
                hasSize(1));
        SemaphoreStep.success("pod/1", null);

        PodTemplate template = templates.get(0);
        List<PodAnnotation> annotations = template.getAnnotations();
        assertNotNull(annotations);
        boolean foundBuildUrl = false;
        for (PodAnnotation pd : annotations) {
            if (pd.getKey().equals("buildUrl")) {
                assertTrue(pd.getValue().contains(p.getUrl()));
                foundBuildUrl = true;
            }
        }
        assertTrue(foundBuildUrl);
        assertEquals(Integer.MAX_VALUE, template.getInstanceCap());
        assertThat(template.getLabelsMap(), hasEntry("jenkins/label", name.getMethodName()));

        Pod pod = pods.getItems().get(0);
        LOGGER.log(Level.INFO, "One pod found: {0}", pod);
        assertThat(pod.getMetadata().getLabels(), hasEntry("jenkins", "slave"));
        assertThat(
                "Pod labels are wrong: " + pod,
                pod.getMetadata().getLabels(),
                hasEntry("jenkins/label", name.getMethodName()));

        SemaphoreStep.waitForStart("after-podtemplate/1", b);
        assertThat(podTemplatesWithLabel(name.getMethodName(), cloud.getAllTemplates()), hasSize(0));
        SemaphoreStep.success("after-podtemplate/1", null);

        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("container=busybox", b);
        r.assertLogContains("script file contents: ", b);
        assertFalse(
                "There are pods leftover after test execution, see previous logs",
                deletePods(cloud.connect(), getLabels(cloud, this, name), true));
        assertThat(
                "routine build should not issue warnings",
                warnings.getRecords().stream()
                        .filter(lr -> lr.getLevel().intValue() >= Level.WARNING.intValue())
                        . // TODO .record(…, WARNING) does not accomplish this
                        map(lr -> lr.getSourceClassName() + "." + lr.getSourceMethodName() + ": " + lr.getMessage())
                        .collect(Collectors.toList()), // LogRecord does not override toString
                emptyIterable());

        assertTrue(Metrics.metricRegistry().counter(MetricNames.PODS_LAUNCHED).getCount() > 0);
    }

    @Test
    public void runIn2Pods() throws Exception {
        SemaphoreStep.waitForStart("podTemplate1/1", b);
        String label1 = name.getMethodName() + "-1";
        PodTemplate template1 =
                podTemplatesWithLabel(label1, cloud.getAllTemplates()).get(0);
        SemaphoreStep.success("podTemplate1/1", null);
        assertEquals(Integer.MAX_VALUE, template1.getInstanceCap());
        assertThat(template1.getLabelsMap(), hasEntry("jenkins/label", label1));
        SemaphoreStep.waitForStart("pod1/1", b);
        Map<String, String> labels1 = getLabels(cloud, this, name);
        labels1.put("jenkins/label", label1);
        PodList pods = cloud.connect().pods().withLabels(labels1).list();
        assertFalse(pods.getItems().isEmpty());
        SemaphoreStep.success("pod1/1", null);

        SemaphoreStep.waitForStart("podTemplate2/1", b);
        String label2 = name.getMethodName() + "-2";
        PodTemplate template2 =
                podTemplatesWithLabel(label2, cloud.getAllTemplates()).get(0);
        SemaphoreStep.success("podTemplate2/1", null);
        assertEquals(Integer.MAX_VALUE, template2.getInstanceCap());
        assertThat(template2.getLabelsMap(), hasEntry("jenkins/label", label2));
        assertNull(label2 + " should not inherit from anything", template2.getInheritFrom());
        SemaphoreStep.waitForStart("pod2/1", b);
        Map<String, String> labels2 = getLabels(cloud, this, name);
        labels1.put("jenkins/label", label2);
        PodList pods2 = cloud.connect().pods().withLabels(labels2).list();
        assertFalse(pods2.getItems().isEmpty());
        SemaphoreStep.success("pod2/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("script file contents: ", b);
        assertFalse(
                "There are pods leftover after test execution, see previous logs",
                deletePods(cloud.connect(), getLabels(cloud, this, name), true));
    }

    @Issue({"JENKINS-57893", "SECURITY-3079"})
    @Test
    public void runInPodFromYaml() throws Exception {
        List<PodTemplate> templates = cloud.getTemplates();
        while (templates.isEmpty()) {
            LOGGER.log(Level.INFO, "Waiting for template to be created");
            templates = cloud.getTemplates();
            Thread.sleep(1000);
        }
        assertFalse(templates.isEmpty());
        PodTemplate template = templates.get(0);
        assertEquals(Integer.MAX_VALUE, template.getInstanceCap());
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("script file contents: ", b);
        r.assertLogNotContains(CONTAINER_ENV_VAR_FROM_SECRET_VALUE, b);
        r.assertLogContains(
                "INSIDE_CONTAINER_ENV_VAR_FROM_SECRET = **** or "
                        + CONTAINER_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT) + "\n",
                b);
        assertFalse(
                "There are pods leftover after test execution, see previous logs",
                deletePods(cloud.connect(), getLabels(cloud, this, name), true));

        // SECURITY-3079
        DurableTaskStep.USE_WATCHING = true;
        try {
            WorkflowRun build = p.scheduleBuild2(0).waitForStart();
            r.assertBuildStatusSuccess(r.waitForCompletion(build));
            r.assertLogNotContains(CONTAINER_ENV_VAR_FROM_SECRET_VALUE, build);
            r.assertLogContains(
                    "INSIDE_CONTAINER_ENV_VAR_FROM_SECRET = **** or "
                            + CONTAINER_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT) + "\n",
                    build);
        } finally {
            DurableTaskStep.USE_WATCHING = false;
        }
    }

    @Test
    public void runInPodWithDifferentShell() throws Exception {
        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b));
        r.assertLogContains("ERROR: Process exited immediately after creation", b);
        // r.assertLogContains("/bin/bash: no such file or directory", b); // Not printed in CI for an unknown reason.
    }

    @Test
    public void bourneShellElsewhereInPath() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("/kaniko:/busybox", b);
    }

    @Test
    public void inheritFrom() throws Exception {
        PodTemplate standard = new PodTemplate();
        standard.setName("standard");
        cloud.addTemplate(standard);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    public void runInPodWithMultipleContainers() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("image: \"maven:3.3.9-jdk-8-alpine\"", b);
        r.assertLogContains("image: \"golang:1.6.3-alpine\"", b);
        r.assertLogContains("My Kubernetes Pipeline", b);
        r.assertLogContains("my-mount", b);
        r.assertLogContains("Apache Maven 3.3.9", b);
    }

    @Test
    public void runInPodNested() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("image: \"maven:3.3.9-jdk-8-alpine\"", b);
        r.assertLogContains("image: \"golang:1.6.3-alpine\"", b);
        r.assertLogContains("Apache Maven 3.3.9", b);
        r.assertLogContains("go version go1.6.3", b);
    }

    @Issue("JENKINS-57548")
    @Test
    public void runInPodNestedExplicitInherit() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("image: \"maven:3.3.9-jdk-8-alpine\"", b);
        r.assertLogNotContains("image: \"golang:1.6.3-alpine\"", b);
        r.assertLogContains("Apache Maven 3.3.9", b);
        r.assertLogNotContains("go version go1.6.3", b);
    }

    @Issue({"JENKINS-57893", "JENKINS-58540"})
    @Test
    public void runInPodWithExistingTemplate() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("outside container", b);
        r.assertLogContains("inside container", b);
        assertEnvVars(r, b);
    }

    @Issue({"JENKINS-57893", "JENKINS-58540"})
    @Test
    public void runWithEnvVariables() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        assertEnvVars(r, b);
        r.assertLogContains("OUTSIDE_CONTAINER_BUILD_NUMBER = 1\n", b);
        r.assertLogContains("INSIDE_CONTAINER_BUILD_NUMBER = 1\n", b);
        r.assertLogContains("OUTSIDE_CONTAINER_JOB_NAME = " + getProjectName() + "\n", b);
        r.assertLogContains("INSIDE_CONTAINER_JOB_NAME = " + getProjectName() + "\n", b);

        // check that we are getting the correct java home
        r.assertLogContains("INSIDE_JAVA_HOME =\n", b);
        /* Varies according to agent image:
        r.assertLogContains("JNLP_JAVA_HOME = /usr/local/openjdk-8\n", b);
        */
        r.assertLogContains("JAVA17_HOME = /opt/java/openjdk\n", b);
        r.assertLogContains("JAVA21_HOME = /opt/java/openjdk\n", b);

        // check that we are not filtering too much
        r.assertLogContains("INSIDE_JAVA_HOME_X = java-home-x\n", b);
        r.assertLogContains("OUTSIDE_JAVA_HOME_X = java-home-x\n", b);
        r.assertLogContains("JNLP_JAVA_HOME_X = java-home-x\n", b);
        r.assertLogContains("JAVA17_HOME_X = java-home-x\n", b);
        r.assertLogContains("JAVA21_HOME_X = java-home-x\n", b);
    }

    @Test
    public void runWithEnvVariablesInContext() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("The initial value of POD_ENV_VAR is pod-env-var-value", b);
        r.assertLogContains("The value of POD_ENV_VAR outside container is /bin/mvn:pod-env-var-value", b);
        r.assertLogContains("The value of FROM_ENV_DEFINITION is ABC", b);
        r.assertLogContains("The value of FROM_WITHENV_DEFINITION is DEF", b);
        r.assertLogContains("The value of WITH_QUOTE is \"WITH_QUOTE", b);
        r.assertLogContains("The value of AFTER_QUOTE is AFTER_QUOTE\"", b);
        r.assertLogContains("The value of ESCAPED_QUOTE is \\\"ESCAPED_QUOTE", b);
        r.assertLogContains("The value of AFTER_ESCAPED_QUOTE is AFTER_ESCAPED_QUOTE\\\"", b);
        r.assertLogContains("The value of SINGLE_QUOTE is BEFORE'AFTER", b);
        r.assertLogContains("The value of WITH_NEWLINE is before newline\nafter newline", b);
        r.assertLogContains("The value of POD_ENV_VAR is /bin/mvn:pod-env-var-value", b);
        r.assertLogContains("The value of WILL.NOT is ", b);
    }

    private void assertEnvVars(JenkinsRule r2, WorkflowRun b) throws Exception {
        r.assertLogNotContains(POD_ENV_VAR_FROM_SECRET_VALUE, b);
        r.assertLogNotContains(CONTAINER_ENV_VAR_FROM_SECRET_VALUE, b);

        r.assertLogContains("INSIDE_CONTAINER_ENV_VAR = " + CONTAINER_ENV_VAR_VALUE + "\n", b);
        r.assertLogContains("INSIDE_CONTAINER_ENV_VAR_LEGACY = " + CONTAINER_ENV_VAR_VALUE + "\n", b);
        r.assertLogContains(
                "INSIDE_CONTAINER_ENV_VAR_FROM_SECRET = **** or "
                        + CONTAINER_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT) + "\n",
                b);
        r.assertLogContains("INSIDE_POD_ENV_VAR = " + POD_ENV_VAR_VALUE + "\n", b);
        r.assertLogContains(
                "INSIDE_POD_ENV_VAR_FROM_SECRET = **** or " + POD_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT)
                        + "\n",
                b);
        r.assertLogContains("INSIDE_EMPTY_POD_ENV_VAR_FROM_SECRET = ''", b);
        r.assertLogContains("INSIDE_GLOBAL = " + GLOBAL + "\n", b);

        r.assertLogContains("OUTSIDE_CONTAINER_ENV_VAR =\n", b);
        r.assertLogContains("OUTSIDE_CONTAINER_ENV_VAR_LEGACY =\n", b);
        r.assertLogContains("OUTSIDE_CONTAINER_ENV_VAR_FROM_SECRET = or\n", b);
        r.assertLogContains("OUTSIDE_POD_ENV_VAR = " + POD_ENV_VAR_VALUE + "\n", b);
        r.assertLogContains(
                "OUTSIDE_POD_ENV_VAR_FROM_SECRET = **** or " + POD_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT)
                        + "\n",
                b);
        r.assertLogContains("OUTSIDE_EMPTY_POD_ENV_VAR_FROM_SECRET = ''", b);
        r.assertLogContains("OUTSIDE_GLOBAL = " + GLOBAL + "\n", b);
    }

    @Test
    public void runWithOverriddenEnvVariables() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("OUTSIDE_CONTAINER_HOME_ENV_VAR = /home/jenkins\n", b);
        r.assertLogContains("INSIDE_CONTAINER_HOME_ENV_VAR = /root\n", b);
        r.assertLogContains("OUTSIDE_CONTAINER_POD_ENV_VAR = " + POD_ENV_VAR_VALUE + "\n", b);
        r.assertLogContains("INSIDE_CONTAINER_POD_ENV_VAR = " + CONTAINER_ENV_VAR_VALUE + "\n", b);
    }

    @Test
    public void supportComputerEnvVars() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("OPENJDK_BUILD_NUMBER: 1\n", b);
        r.assertLogContains("JNLP_BUILD_NUMBER: 1\n", b);
        r.assertLogContains("DEFAULT_BUILD_NUMBER: 1\n", b);
    }

    @Test
    public void runDirContext() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        String workspace = "/home/jenkins/agent/workspace/" + getProjectName();
        r.assertLogContains("initpwd is -" + workspace + "-", b);
        r.assertLogContains("dirpwd is -" + workspace + "/hz-", b);
        r.assertLogContains("postpwd is -" + workspace + "-", b);
    }

    @Test
    public void runInPodWithLivenessProbe() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("Still alive", b);
    }

    @Test
    public void podTemplateWithMultipleLabels() throws Exception {
        PodTemplate pt = new PodTemplate();
        pt.setName("podTemplate");
        pt.setLabel("label1 label2");
        cloud.addTemplate(pt);
        SemaphoreStep.waitForStart("pod/1", b);
        Map<String, String> labels = getLabels(cloud, this, name);
        labels.put("jenkins/label", "label1_label2");
        KubernetesSlave node = r.jenkins.getNodes().stream()
                .filter(KubernetesSlave.class::isInstance)
                .map(KubernetesSlave.class::cast)
                .findAny()
                .get();
        assertTrue(node.getAssignedLabels().containsAll(Label.parse("label1 label2")));
        PodList pods = cloud.connect().pods().withLabels(labels).list();
        assertThat(
                "Expected one pod with labels " + labels + " but got: "
                        + pods.getItems().stream()
                                .map(Pod::getMetadata)
                                .map(ObjectMeta::getName)
                                .collect(Collectors.toList()),
                pods.getItems(),
                hasSize(1));
        SemaphoreStep.success("pod/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    public void runWithActiveDeadlineSeconds() throws Exception {
        SemaphoreStep.waitForStart("podTemplate/1", b);
        PodTemplate deadlineTemplate = cloud.getAllTemplates().stream()
                .filter(x -> name.getMethodName().equals(x.getLabel()))
                .findAny()
                .orElse(null);
        assertNotNull(deadlineTemplate);
        SemaphoreStep.success("podTemplate/1", null);
        assertEquals(10, deadlineTemplate.getActiveDeadlineSeconds());
        b.getExecutor().interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
    }

    @Test
    public void runInPodWithRetention() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        System.out.println("Deleting leftover pods");
        KubernetesClient client = cloud.connect();
        Map<String, String> labels = getLabels(this, name);
        assertTrue(client.pods().withLabels(labels).delete().size() > 0);
    }

    @Issue("JENKINS-49707")
    @Test
    public void terminatedPod() throws Exception {
        logs.record(KubernetesAgentErrorCondition.class, Level.FINE);
        r.waitForMessage("+ sleep", b);
        deletePods(cloud.connect(), getLabels(this, name), false);
        r.waitForMessage("busybox --", b);
        r.waitForMessage("jnlp --", b);
        r.waitForMessage("was deleted; cancelling node body", b);
        r.waitForMessage("Retrying", b);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Issue("JENKINS-59340")
    @Test
    public void containerTerminated() throws Exception {
        assertBuildStatus(r.waitForCompletion(b), Result.FAILURE, Result.ABORTED);
        r.waitForMessage("Container stress-ng was terminated", b);
        /* TODO sometimes instead get: Container stress-ng was terminated (Exit Code: 0, Reason: Completed)
        r.waitForMessage("Reason: OOMKilled", b);
        */
    }

    @Test
    public void errorPod() throws Exception {
        r.waitForMessage("jnlp -- terminated (1)", b);
        r.waitForMessage("Foo", b);
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
    }

    @Issue("JENKINS-59340")
    @Test
    public void podDeadlineExceeded() throws Exception {
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.waitForMessage(POD_DEADLINE_EXCEEDED_MESSAGE, b);
    }

    @Test
    public void podDeadlineExceededGlobalTemplate() throws Exception {
        PodTemplate podTemplate = new PodTemplate("podDeadlineExceededGlobalTemplate");
        podTemplate.setLabel("podDeadlineExceededGlobalTemplate");
        podTemplate.setActiveDeadlineSeconds(30);
        cloud.addTemplate(podTemplate);
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.waitForMessage(POD_DEADLINE_EXCEEDED_MESSAGE, b);
        r.waitForMessage("---Logs---", b);
    }

    @Test
    public void interruptedPod() throws Exception {
        r.waitForMessage("starting to sleep", b);
        b.getExecutor().interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("shut down gracefully", b);
    }

    @Issue("JENKINS-58306")
    @Test
    public void cascadingDelete() throws Exception {
        try {
            cloud.connect().apps().deployments().withName("cascading-delete").delete();
            assumeNotNull(cloud.connect().serviceAccounts().withName("jenkins").get());
        } catch (KubernetesClientException x) {
            // Failure executing: DELETE at:
            // https://…/apis/apps/v1/namespaces/kubernetes-plugin-test/deployments/cascading-delete. Message:
            // Forbidden!Configured service account doesn't have access. Service account may have been revoked.
            // deployments.apps "cascading-delete" is forbidden: User "system:serviceaccount:…:…" cannot delete resource
            // "deployments" in API group "apps" in the namespace "kubernetes-plugin-test".
            assumeNoException(
                    "was not permitted to clean up any previous deployment, so presumably cannot run test either", x);
        }
        cloud.connect()
                .apps()
                .replicaSets()
                .withLabel("app", "cascading-delete")
                .delete();
        cloud.connect().pods().withLabel("app", "cascading-delete").delete();
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    @Ignore
    public void computerCantBeConfigured() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.MANAGE)
                .everywhere()
                .to("admin"));
        SemaphoreStep.waitForStart("pod/1", b);
        Optional<KubernetesSlave> optionalNode = r.jenkins.getNodes().stream()
                .filter(KubernetesSlave.class::isInstance)
                .map(KubernetesSlave.class::cast)
                .findAny();
        assertTrue(optionalNode.isPresent());
        KubernetesSlave node = optionalNode.get();

        JenkinsRule.WebClient wc = r.createWebClient();
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.login("admin");

        HtmlPage nodeIndex = wc.getPage(node);
        assertNotXPath(nodeIndex, "//*[text() = 'configure']");
        r.assertXPath(nodeIndex, "//*[text() = 'View Configuration']");
        SemaphoreStep.success("pod/1", null);
    }

    private void assertNotXPath(HtmlPage page, String xpath) {
        HtmlElement documentElement = page.getDocumentElement();
        assertNull(
                "There should not be an object that matches XPath:" + xpath,
                DomNodeUtil.selectSingleNode(documentElement, xpath));
    }

    @Issue("JENKINS-57717")
    @Test
    public void runInPodWithShowRawYamlFalse() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogNotContains("value: \"container-env-var-value\"", b);
    }

    @Issue("JENKINS-58574")
    @Test
    public void showRawYamlFalseInherited() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogNotContains("value: \"container-env-var-value\"", b);
    }

    @Test
    @Issue("JENKINS-58405")
    public void overrideYaml() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    @Issue("JENKINS-58405")
    public void mergeYaml() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    @Issue("JENKINS-58602")
    public void jenkinsSecretHidden() throws Exception {
        SemaphoreStep.waitForStart("pod/1", b);
        Optional<SlaveComputer> scOptional = Arrays.stream(r.jenkins.getComputers())
                .filter(SlaveComputer.class::isInstance)
                .map(SlaveComputer.class::cast)
                .findAny();
        assertTrue(scOptional.isPresent());
        String jnlpMac = scOptional.get().getJnlpMac();
        SemaphoreStep.success("pod/1", b);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogNotContains(jnlpMac, b);
    }

    @Test
    public void jnlpWorkingDir() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Issue("JENKINS-61178")
    @Test
    public void sidecarWorkingDir() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Issue("JENKINS-60517")
    @Test
    public void runInDynamicallyCreatedContainer() throws Exception {
        List<PodTemplate> templates = cloud.getTemplates();
        while (templates.isEmpty()) {
            LOGGER.log(Level.INFO, "Waiting for template to be created");
            templates = cloud.getTemplates();
            Thread.sleep(1000);
        }
        assertFalse(templates.isEmpty());
        PodTemplate template = templates.get(0);
        assertEquals(Integer.MAX_VALUE, template.getInstanceCap());
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("whoami", b);
        r.assertLogContains("root", b);
    }

    @Issue("JENKINS-57256")
    @Test
    public void basicWindows() throws Exception {
        assumeWindows(WINDOWS_1809_BUILD);
        cloud.setDirectConnection(false); // not yet supported by
        // https://github.com/jenkinsci/docker-inbound-agent/blob/517ccd68fd1ce420e7526ca6a40320c9a47a2c18/jenkins-agent.ps1
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("Directory of C:\\home\\jenkins\\agent\\workspace\\basic Windows", b); // bat
        r.assertLogContains("C:\\Program Files (x86)", b); // powershell
    }

    @Issue("JENKINS-53500")
    @Test
    public void windowsContainer() throws Exception {
        assumeWindows(WINDOWS_1809_BUILD);
        cloud.setDirectConnection(false);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains("Directory of C:\\home\\jenkins\\agent\\workspace\\windows Container\\subdir", b);
        r.assertLogContains("C:\\Users\\ContainerAdministrator", b);
        r.assertLogContains("got stuff: some value", b);
    }

    @Ignore(
            "TODO aborts, but with “kill finished with exit code 9009” and “After 20s process did not stop” and no graceful shutdown")
    @Test
    public void interruptedPodWindows() throws Exception {
        assumeWindows(WINDOWS_1809_BUILD);
        cloud.setDirectConnection(false);
        r.waitForMessage("starting to sleep", b);
        b.getExecutor().interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("shut down gracefully", b);
    }

    @Test
    public void secretMaskingWindows() throws Exception {
        assumeWindows(WINDOWS_1809_BUILD);
        cloud.setDirectConnection(false);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        r.assertLogContains(
                "INSIDE_POD_ENV_VAR_FROM_SECRET = **** or " + POD_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT),
                b);
        r.assertLogContains(
                "INSIDE_CONTAINER_ENV_VAR_FROM_SECRET = **** or "
                        + CONTAINER_ENV_VAR_FROM_SECRET_VALUE.toUpperCase(Locale.ROOT),
                b);
        r.assertLogNotContains(POD_ENV_VAR_FROM_SECRET_VALUE, b);
        r.assertLogNotContains(CONTAINER_ENV_VAR_FROM_SECRET_VALUE, b);
    }

    @Test
    public void dynamicPVCWorkspaceVolume() throws Exception {
        dynamicPVC();
    }

    @Test
    public void dynamicPVCVolume() throws Exception {
        dynamicPVC();
    }

    private void dynamicPVC() throws Exception {
        assumePvcAccess();
        var client = cloud.connect();
        SemaphoreStep.waitForStart("before/1", b);
        var pods = getPodNames(client);
        assertThat(pods, empty());
        var pvcs = getPvcNames(client);
        SemaphoreStep.success("before/1", null);
        SemaphoreStep.waitForStart("pod/1", b);
        assertThat(getPodNames(client), hasSize(1));
        assertThat(getPvcNames(client), hasSize(1));
        SemaphoreStep.success("pod/1", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        await("The pods should be the same as before building")
                .timeout(Duration.ofMinutes(1))
                .until(() -> getPodNames(client), equalTo(pods));
        await("The PVCs should be the same as before building")
                .timeout(Duration.ofMinutes(1))
                .until(() -> getPvcNames(client), equalTo(pvcs));
    }

    private @NonNull Set<String> getPvcNames(KubernetesClient client) {
        return client.persistentVolumeClaims().withLabels(getTestLabels()).list().getItems().stream()
                .map(pvc -> pvc.getMetadata().getName())
                .collect(Collectors.toSet());
    }

    private @NonNull Set<String> getPodNames(KubernetesClient client) {
        return client.pods().withLabels(getTestLabels()).list().getItems().stream()
                .map(pod -> pod.getMetadata().getName())
                .collect(Collectors.toSet());
    }

    private @NonNull Map<String, String> getTestLabels() {
        return KubernetesTestUtil.getLabels(cloud, this, name);
    }

    private void assumePvcAccess() throws KubernetesAuthException, IOException {
        try {
            cloud.connect().persistentVolumeClaims().list();
        } catch (KubernetesClientException x) {
            // Error from server (Forbidden): persistentvolumeclaims is forbidden: User
            // "system:serviceaccount:kubernetes-plugin-test:default" cannot list resource "persistentvolumeclaims" in
            // API group "" in the namespace "kubernetes-plugin-test"
            assumeNoException("was not permitted to list pvcs, so presumably cannot run test either", x);
        }
    }

    @Test
    public void invalidPodGetsCancelled() throws Exception {
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("ERROR: Unable to create pod", b);
        r.assertLogContains("Queue task was cancelled", b);
    }

    @Test
    public void invalidImageGetsCancelled() throws Exception {
        Reaper.TerminateAgentOnImagePullBackOff.BACKOFF_EVENTS_LIMIT = 2;
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("Image pull backoff detected, waiting for image to be available.", b);
        r.assertLogContains("Queue task was cancelled", b);
    }

    @Issue("SECURITY-1646")
    @Test
    public void substituteEnv() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
        String home = System.getenv("HOME");
        assumeNotNull(home);
        r.assertLogContains("hack: \"xxx${HOME}xxx\"", b);
        r.assertLogNotContains("xxx" + home + "xxx", b);
        PodTemplateUtils.SUBSTITUTE_ENV = true;
        b = r.buildAndAssertSuccess(p);
        r.assertLogContains("xxx" + home + "xxx", b);
    }

    @Test
    public void octalPermissions() throws Exception {
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    private <R extends Run> R assertBuildStatus(R run, Result... status) throws Exception {
        for (Result s : status) {
            if (s == run.getResult()) {
                return run;
            }
        }
        String msg = "unexpected build status; build log was:\n------\n" + r.getLog(run) + "\n------\n";
        MatcherAssert.assertThat(msg, run.getResult(), is(oneOf(status)));
        return run;
    }

    @Test
    public void cancelOnlyRelevantQueueItem() throws Exception {
        r.waitForMessage("cancelled pod item by now", b);
        r.createOnlineSlave(Label.get("special-agent"));
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("ran on special agent", b);
    }

    @Test
    public void garbageCollection() throws Exception {
        // Pod exists, need to kill the build, delete the agent without deleting the pod.
        // Wait for the timeout to expire and check that the pod is deleted.
        var garbageCollection = new GarbageCollection();
        // Considering org.csanchez.jenkins.plugins.kubernetes.GarbageCollection.recurrencePeriod=5, this leaves 3 ticks
        garbageCollection.setTimeout(15);
        cloud.setGarbageCollection(garbageCollection);
        r.jenkins.save();
        r.waitForMessage("Running on remote agent", b);
        Pod pod = null;
        for (var c : r.jenkins.getComputers()) {
            if (c instanceof KubernetesComputer) {
                var node = (KubernetesSlave) c.getNode();
                pod = node.getPod().get();
                Assert.assertNotNull(pod);
                b.doKill();
                r.jenkins.removeNode(node);
                break;
            }
        }
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        final var finalPod = pod;
        var client = cloud.connect();
        assertNotNull(client.resource(finalPod).get());
        await().timeout(1, TimeUnit.MINUTES)
                .until(() -> client.resource(finalPod).get() == null);
    }

    @Test
    public void handleEviction() throws Exception {
        SemaphoreStep.waitForStart("pod/1", b);
        var client = cloud.connect();
        var pod = client.pods()
                .withLabels(getLabels(cloud, this, name))
                .list()
                .getItems()
                .get(0);
        client.pods().resource(pod).evict();
        r.waitForMessage("Pod was evicted by the Kubernetes Eviction API", b);
        SemaphoreStep.success("pod/1", null);
        SemaphoreStep.waitForStart("pod/2", b);
        SemaphoreStep.success("pod/2", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Test
    public void decoratorFailure() throws Exception {
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("I always fail", b);
        assertThat("Node should have been removed", r.jenkins.getNodes(), empty());
    }

    @TestExtension("decoratorFailure")
    public static class DecoratorImpl implements PodDecorator {
        @NonNull
        @Override
        public Pod decorate(@NonNull KubernetesCloud kubernetesCloud, @NonNull Pod pod) {
            throw new PodDecoratorException("I always fail");
        }
    }

    @Test
    public void imageWithoutAgent() throws Exception {
        r.assertBuildStatus(Result.SUCCESS, r.waitForCompletion(b));
    }

    @Test
    public void imageWithoutAgentNoJava() throws Exception {
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b));
        r.assertLogContains("java: not found", b);
    }
}
