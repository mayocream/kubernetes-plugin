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
import io.fabric8.kubernetes.api.model.Pod;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import org.csanchez.jenkins.plugins.kubernetes.ContainerTemplate;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.pod.decorator.PodDecorator;
import org.csanchez.jenkins.plugins.kubernetes.pod.decorator.PodDecoratorException;

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
                var f = new FilePath(launcher.getChannel(), loc(container));
                try {
                    // TODO envs, masks, stdout, …
                    // TODO better quote pwd
                    var sb = new StringBuffer("cd '").append(starter.pwd()).append("';");
                    for (var cmd : starter.cmds()) {
                        cmd = cmd.replace("$$", "$"); // undo BourneShellScript.scriptLauncherCmd
                        LOGGER.info("TODO " + cmd);
                        sb.append(" '");
                        for (int i = 0; i < cmd.length(); i++) {
                            char c = cmd.charAt(i);
                            if (c == '\'') {
                                sb.append("'\"'\"'");
                            } else {
                                sb.append(c);
                            }
                        }
                        sb.append("'");
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

    // TODO look up actual workspace location
    // TODO create separate file per launch, so processes could run in parallel
    private static String loc(String containerName) {
        return ContainerTemplate.DEFAULT_WORKING_DIR + "/listen-" + containerName + ".sh";
    }

    @Extension
    public static final class Decorator implements PodDecorator {

        @Override
        public Pod decorate(KubernetesCloud kubernetesCloud, Pod pod) {
            if (ENABLED) {
                // TODO exclude Windows
                for (var c : pod.getSpec().getContainers()) {
                    if (c.getCommand().size() == 1 && c.getCommand().get(0).matches("((/usr)?/bin/)?(sleep|cat)")) {
                        c.setCommand(List.of("sh"));
                        var name = c.getName();
                        var loc = loc(name);
                        String script;
                        try {
                            script = new String(ContainerListenDecorator.class
                                    .getResourceAsStream("scripts/container-listen.sh")
                                    .readAllBytes());
                        } catch (IOException x) {
                            throw new PodDecoratorException(null, x);
                        }
                        // TODO set container env var instead of prepending to script
                        c.setArgs(List.of("-c", "LOC='" + loc + "'\n" + script));
                        LOGGER.info(() -> "adjusted container " + name + " in "
                                + pod.getMetadata().getName());
                    } else {
                        LOGGER.info("TODO " + c + " does not match");
                    }
                }
            }
            return pod;
        }
    }
}
