package io.jenkins.plugins.clever;

import hudson.slaves.AbstractCloudComputer;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverComputer extends AbstractCloudComputer<CleverAgent> {

    private static final Logger LOGGER = Logger.getLogger(CleverComputer.class.getName());

    // once created CleverCloudAgent is never reconfigured, so we can keep a reference like this.
    private final CleverAgent agent;

    public CleverComputer(CleverAgent agent) {
        super(agent);
        this.agent = agent;
    }

    @CheckForNull
    @Override
    public CleverAgent getNode() {
        return agent;
    }

    @Override
    protected void onRemoved() {
        threadPoolForRemoting.submit(() -> {
                try {
                    agent.terminate();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to terminate " + getDisplayName(), e);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Failed to terminate " + getDisplayName(), e);
                }
        });
    }

}
