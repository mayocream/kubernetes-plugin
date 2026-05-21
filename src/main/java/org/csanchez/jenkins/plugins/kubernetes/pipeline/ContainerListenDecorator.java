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
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import jenkins.util.SystemProperties;
import org.csanchez.jenkins.plugins.kubernetes.ContainerTemplate;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
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
        if (node != null && !(node instanceof KubernetesSlave)) {
            return launcher;
        }
        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(Launcher.ProcStarter starter) throws IOException {
                if (!launcher.isUnix()) {
                    throw new AbortException("TODO Windows not yet supported");
                }
                FilePath f;
                try {
                    var work = new FilePath(launcher.getChannel(), work());
                    var bootstrap = work.child("bootstrap.sh");
                    if (!bootstrap.exists()) {
                        work.mkdirs();
                        bootstrap.copyFrom(
                                ContainerListenDecorator.class.getResource("scripts/container-bootstrap.sh"));
                        LOGGER.info("TODO created " + bootstrap);
                    }
                    var procDir = work.child(container)
                            .child("%016x"
                                    .formatted(RandomGenerator.getDefault().nextLong()));
                    procDir.mkdirs();
                    f = procDir.child("script.sh");
                    var sb = new StringBuilder();
                    var pwd = starter.pwd();
                    if (pwd != null) {
                        sb.append("cd ");
                        quote(sb, pwd.getRemote());
                        sb.append("\n");
                    }
                    var envVars = starter.envs();
                    if (node != null) {
                        var c = node.toComputer();
                        if (c != null) {
                            // Remove env vars which are simply inherited from the agent container’s environment.
                            // Some of these like $JAVA_HOME could be unnecessary or even harmful in other containers.
                            var agentEnv = c.getEnvironment();
                            envVars = Stream.of(envVars)
                                    .filter(kv -> {
                                        var split = kv.split("=", 2);
                                        return split.length == 2 && !split[1].equals(agentEnv.get(split[0]));
                                    })
                                    .toArray(String[]::new);
                        }
                    }
                    for (var env : envVars) {
                        sb.append("export ");
                        quote(sb, env);
                        sb.append("\n");
                    }
                    // TODO masks, stdout, …
                    for (var cmd : starter.cmds()) {
                        cmd = cmd.replace("$$", "$"); // undo BourneShellScript.scriptLauncherCmd
                        quote(sb, cmd);
                        sb.append(" ");
                    }
                    f.write(sb.toString(), null);
                    LOGGER.info("TODO wrote to " + f + ": \n" + sb);
                } catch (InterruptedException x) {
                    throw new IOException(x);
                }
                return new Proc() {
                    @Override
                    public boolean isAlive() throws IOException, InterruptedException {
                        return f.exists();
                    }

                    @Override
                    public void kill() throws IOException, InterruptedException {
                        // TODO
                    }

                    @Override
                    public int join() throws IOException, InterruptedException {
                        // TODO capture exit code
                        return 0;
                    }

                    @Override
                    public InputStream getStdout() {
                        // TODO
                        return InputStream.nullInputStream();
                    }

                    @Override
                    public InputStream getStderr() {
                        return InputStream.nullInputStream();
                    }

                    @Override
                    public OutputStream getStdin() {
                        return OutputStream.nullOutputStream();
                    }
                };
            }
        };
    }

    @Override
    public void close() throws IOException {
        // anything to do?
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

    // TODO look up actual mountPath of workspace-volume mount
    private static String work() {
        return ContainerTemplate.DEFAULT_WORKING_DIR + "/container-work";
    }

    @Extension
    public static final class Decorator implements PodDecorator {

        @Override
        public Pod decorate(KubernetesCloud kubernetesCloud, Pod pod) {
            if (ENABLED) {
                // TODO exclude Windows
                for (var c : pod.getSpec().getContainers()) {
                    var cmds = c.getCommand();
                    if (cmds != null && cmds.size() == 1 && cmds.get(0).matches("((/usr)?/bin/)?(sleep|cat)")) {
                        var name = c.getName();
                        c.setCommand(List.of("sh"));
                        c.setArgs(
                                List.of(
                                        "-c",
                                        "until test -f \"$JENKINS_CONTAINER_WORK\"/bootstrap.sh; do sleep 1; done; . \"$JENKINS_CONTAINER_WORK\"/bootstrap.sh"));
                        var env = new ArrayList<>(c.getEnv());
                        env.add(new EnvVar("JENKINS_CONTAINER_WORK", work(), null));
                        env.add(new EnvVar("JENKINS_CONTAINER_NAME", name, null));
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
