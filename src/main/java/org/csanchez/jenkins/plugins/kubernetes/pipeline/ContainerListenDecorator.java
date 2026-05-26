/*
 * Copyright 2026 CloudBees, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import hudson.AbortException;
import hudson.CloseProofOutputStream;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import jenkins.util.SystemProperties;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateBuilder;
import org.csanchez.jenkins.plugins.kubernetes.pod.decorator.PodDecorator;

/**
 * Alternative to {@link ContainerExecDecorator} which does not rely on the API server.
 * “Sleepy” containers are automatically switched to listen for commands from the agent container.
 */
final class ContainerListenDecorator extends LauncherDecorator implements Serializable, Closeable {

    static final boolean ENABLED =
            SystemProperties.getBoolean(ContainerListenDecorator.class.getName() + ".enabled", true);

    private static final Logger LOGGER = Logger.getLogger(ContainerListenDecorator.class.getName());

    @Serial
    private static final long serialVersionUID = 1;

    private final String container;

    ContainerListenDecorator(String container) {
        this.container = container;
    }

    @Override
    public Launcher decorate(Launcher launcher, Node node) {
        if (!(node instanceof KubernetesSlave ks)) {
            LOGGER.warning(() -> "container step used on unexpected node " + node);
            return launcher;
        }
        var podO = ks.getPod();
        if (!podO.isPresent()) {
            LOGGER.warning(() -> "container step used " + node + " but Pod could not be looked up");
            return launcher;
        }
        var pod = podO.get();
        launcher = undecorate(launcher);
        try {
            var c = pod.getSpec().getContainers().stream()
                    .filter(_c -> container.equals(_c.getName()))
                    .findFirst()
                    .orElse(null);
            if (c != null) {
                if (c.getEnv() == null
                        || !c.getEnv().stream().anyMatch(e -> e.getName().equals("JENKINS_CONTAINER_NAME"))) {
                    launcher.getListener()
                            .getLogger()
                            .println("Warning: container step on unpatched container " + c.getName());
                    return launcher;
                }
            } else {
                launcher.getListener().getLogger().println("Warning: could not find container " + container);
                return launcher;
            }
        } catch (Exception x) {
            LOGGER.log(
                    Level.WARNING,
                    x,
                    () -> "Could not verify eligibility of container " + container + " in " + ks.getPodName());
        }
        LOGGER.info("TODO prepping launcher for " + container);
        return new LauncherImpl(launcher, ks, pod);
    }

    private static final Launcher undecorate(Launcher launcher) {
        if (launcher instanceof LauncherImpl decorated) {
            return undecorate(decorated.getInner());
        } else {
            return launcher;
        }
    }

    @Override
    public void close() throws IOException {
        // anything to do?
    }

    private class LauncherImpl extends Launcher.DecoratedLauncher {
        private final KubernetesSlave ks;
        private final Pod pod;

        public LauncherImpl(Launcher base, KubernetesSlave ks, Pod pod) {
            super(base);
            this.ks = ks;
            this.pod = pod;
        }

        @Override
        public Proc launch(Launcher.ProcStarter starter) throws IOException {
            if (!isUnix()) {
                throw new AbortException("TODO Windows not yet supported");
            }
            FilePath procDir;
            try {
                var workspaceToAgent = workspaceVolumeMountPath(pod, c -> c.getEnv().stream()
                                .anyMatch(e -> e.getName().equals("JENKINS_AGENT_WORKDIR")))
                        .findFirst()
                        .orElseThrow(() -> new IOException("Cannot find JENKINS_AGENT_WORKDIR in " + ks.getPodName()));
                var work = new FilePath(getChannel(), workspaceToAgent + "/container-work");
                var bootstrap = work.child("bootstrap.sh");
                if (!bootstrap.exists()) {
                    work.mkdirs();
                    bootstrap.copyFrom(ContainerListenDecorator.class.getResource("scripts/container-bootstrap.sh"));
                    LOGGER.info("TODO created " + bootstrap);
                }
                procDir = work.child(container)
                        .child("%016x".formatted(RandomGenerator.getDefault().nextLong()));
                procDir.mkdirs();
                var f = procDir.child("script.sh");
                var sb = new StringBuilder();
                var pwdF = starter.pwd();
                var pwd = pwdF != null ? pwdF.getRemote() : null;
                var envVars = starter.envs();
                var kc = ks.toComputer();
                if (kc != null) {
                    // Remove env vars which are simply inherited from the agent container’s environment.
                    // Some of these like $JAVA_HOME could be unnecessary or even harmful in other containers.
                    var agentEnv = kc.getEnvironment();
                    envVars = Stream.of(envVars)
                            .filter(kv -> {
                                var split = kv.split("=", 2);
                                return split.length == 2 && !split[1].equals(agentEnv.get(split[0]));
                            })
                            .toArray(String[]::new);
                }
                var cmds = starter.cmds();
                var workspaceToContainer = workspaceVolumeMountPath(
                                pod, c -> c.getName().equals(container))
                        .findFirst()
                        .orElse(null);
                if (workspaceToContainer != null && !workspaceToContainer.equals(workspaceToAgent)) {
                    LOGGER.info("TODO translating " + workspaceToAgent + " to " + workspaceToContainer);
                    if (pwd.startsWith(workspaceToAgent + "/")) {
                        pwd = workspaceToContainer + pwd.substring(workspaceToAgent.length());
                    }
                    // e.g., various paths created by BourneShellScript wrapper:
                    cmds = cmds.stream()
                            .map(s -> s.replaceAll(workspaceToAgent, workspaceToContainer))
                            .toList();
                    // $WORKSPACE, $WORKSPACE_TMP
                    envVars = Stream.of(envVars)
                            .map(s -> s.replaceAll(workspaceToAgent, workspaceToContainer))
                            .toArray(String[]::new);
                }
                if (pwd != null) {
                    sb.append("cd ");
                    quote(sb, pwd);
                    sb.append("\n");
                }
                for (var env : envVars) {
                    sb.append("export ");
                    quote(sb, env);
                    sb.append("\n");
                }
                for (var cmd : cmds) {
                    cmd = cmd.replace("$$", "$"); // undo BourneShellScript.scriptLauncherCmd
                    quote(sb, cmd);
                    sb.append(" ");
                }
                f.write(sb.toString(), null);
                LOGGER.info("TODO wrote to " + f + ": \n" + sb);
                if (!starter.quiet()) {
                    maskedPrintCommandLine(
                            cmds, starter.masks(), pwd != null ? new FilePath(pwdF.getChannel(), pwd) : null);
                }
            } catch (InterruptedException x) {
                throw new IOException(x);
            }
            return new Proc() {
                @Override
                public boolean isAlive() throws IOException, InterruptedException {
                    return procDir.child("seen").exists()
                            && !procDir.child("status.txt").exists();
                }

                @Override
                public void kill() throws IOException, InterruptedException {
                    // TODO could touch some death flag
                    procDir.child("status.txt").write("-1", null);
                }

                @Override
                public int join() throws IOException, InterruptedException {
                    var status = procDir.child("status.txt");
                    LOGGER.info("TODO waiting for " + status);
                    while (!status.exists()) {
                        Thread.sleep(100);
                    }
                    // No streaming supported, just logging/capturing of stdio from a completed process.
                    // In practice most steps are durable, which produce no stdio themselves;
                    // or relatively brief and so streaming is unnecessary.
                    LOGGER.info("TODO " + procDir + " completed with status "
                            + status.readToString().trim() + " and stdout "
                            + procDir.child("out.txt").length() + "b stderr"
                            + procDir.child("err.txt").length() + "b");
                    var os = starter.stdout();
                    if (os == null) {
                        os = getListener().getLogger();
                    }
                    procDir.child("out.txt").copyTo(new CloseProofOutputStream(os));
                    os = starter.stderr();
                    if (os == null) {
                        os = getListener().getLogger();
                    }
                    procDir.child("err.txt").copyTo(new CloseProofOutputStream(os));
                    var r = Integer.parseInt(status.readToString().trim());
                    procDir.deleteRecursive();
                    return r;
                }

                @Override
                public InputStream getStdout() {
                    return null;
                }

                @Override
                public InputStream getStderr() {
                    return null;
                }

                @Override
                public OutputStream getStdin() {
                    return OutputStream.nullOutputStream();
                }
            };
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\"'\"'"); // or '\''
            } else {
                sb.append(c);
            }
        }
        sb.append("'");
    }

    private static Stream<String> workspaceVolumeMountPath(Container container) {
        return container.getVolumeMounts().stream()
                .filter(vm -> vm.getName().equals(PodTemplateBuilder.WORKSPACE_VOLUME_NAME))
                .map(VolumeMount::getMountPath);
    }

    private static Stream<String> workspaceVolumeMountPath(Pod pod, Predicate<Container> container) {
        return pod.getSpec().getContainers().stream()
                .flatMap(c -> container.test(c) ? workspaceVolumeMountPath(c) : Stream.of());
    }

    @Extension
    public static final class Decorator implements PodDecorator {

        @Override
        public Pod decorate(KubernetesCloud kubernetesCloud, Pod pod) {
            if (ENABLED) {
                // TODO exclude Windows
                for (var c : pod.getSpec().getContainers()) {
                    var cmds = c.getCommand();
                    if (cmds != null && !cmds.isEmpty() && cmds.get(0).matches("((/usr)?/bin/)?(sleep|cat)")) {
                        var name = c.getName();
                        var workspace = workspaceVolumeMountPath(c).findFirst().orElse(null);
                        if (workspace == null) {
                            LOGGER.warning(() -> "Cannot find " + PodTemplateBuilder.WORKSPACE_VOLUME_NAME + " in "
                                    + name + " in " + pod.getMetadata().getName());
                            continue;
                        }
                        c.setCommand(List.of("sh"));
                        c.setArgs(
                                List.of(
                                        "-c",
                                        "until test -f \"$JENKINS_CONTAINER_WORK\"/bootstrap.sh; do sleep 1; done; . \"$JENKINS_CONTAINER_WORK\"/bootstrap.sh"));
                        var env = new ArrayList<>(c.getEnv() != null ? c.getEnv() : List.of());
                        env.add(new EnvVar("JENKINS_CONTAINER_NAME", name, null));
                        env.add(new EnvVar("JENKINS_CONTAINER_WORK", workspace + "/container-work", null));
                        c.setEnv(env);
                        LOGGER.info(() -> "adjusted container " + name + " in "
                                + pod.getMetadata().getName());
                    } else {
                        LOGGER.fine(() -> c + " does not match");
                    }
                }
            }
            return pod;
        }
    }
}
