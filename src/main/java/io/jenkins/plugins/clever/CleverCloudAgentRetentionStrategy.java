package io.jenkins.plugins.clever;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.CloudSlaveRetentionStrategy;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloudAgentRetentionStrategy extends CloudSlaveRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(CleverCloudAgentRetentionStrategy.class.getName());

    @Override
    public boolean isManualLaunchAllowed(Computer c) {
        return false;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // noop
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        final CleverCloudComputer c = (CleverCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();
        /**
        if (exec instanceof ContinuableExecutable && ((ContinuableExecutable) exec).willContinue()) {
            LOGGER.log(Level.FINE, "not terminating {0} because {1} says it will be continued", new Object[]{c.getName(), exec});
            return;
        }
         */
        LOGGER.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[]{c.getName(), exec});
        c.setAcceptingTasks(false); // just in case
        Computer.threadPoolForRemoting.submit(() -> {
            Queue.withLock( () -> {
                CleverCloudAgent node = c.getNode();
                if (node != null) {
                    try {
                        node.terminate();
                    } catch (InterruptedException | IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to terminate agent "+node.getNodeName(), e);
                    }

                }
            });
        });
    }
}
