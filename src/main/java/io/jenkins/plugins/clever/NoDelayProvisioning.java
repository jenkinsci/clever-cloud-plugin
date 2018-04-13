package io.jenkins.plugins.clever;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.model.queue.SubTask;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

/**
 * As a task enter the queue and will scheduled _soon_, wait a few then suggest {@link NodeProvisioner}
 * to review the build queue.
 * 
 * Based on <a href="https://github.com/jenkinsci/docker-plugin/issues/284#issuecomment-123370896">Stephen's suggestion</a>
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class NoDelayProvisioning extends QueueListener {

    @Override
    public void onEnterBuildable(Queue.BuildableItem bi) {
        final Jenkins jenkins = Jenkins.getInstance();

        for (SubTask subTask : bi.task.getSubTasks()) {
            final Label assignedLabel = subTask.getAssignedLabel();

            if (assignedLabel != null && assignedLabel.getIdleExecutors() == 0) {
                for (Cloud cloud : jenkins.clouds) {
                    if (cloud.canProvision(assignedLabel)) {
                        final NodeProvisioner provisioner = assignedLabel.nodeProvisioner;

                        Computer.threadPoolForRemoting.submit( () -> {
                            if (provisioner.getPendingLaunches().isEmpty()) {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) { }
                                provisioner.suggestReviewNow();
                            }
                        });
                    }
                }
            }
        }

    }
}
