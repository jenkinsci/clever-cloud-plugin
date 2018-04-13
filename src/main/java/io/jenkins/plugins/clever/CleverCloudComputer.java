package io.jenkins.plugins.clever;

import hudson.slaves.AbstractCloudComputer;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloudComputer extends AbstractCloudComputer<CleverCloudAgent> {

    private static final Logger LOGGER = Logger.getLogger(CleverCloudComputer.class.getName());

    // once created CleverCloudAgent is never reconfigured, so we can keep a reference like this.
    private final CleverCloudAgent agent;

    public CleverCloudComputer(CleverCloudAgent agent) {
        super(agent);
        this.agent = agent;
    }

    public String getApplicationId() {
        return agent.getApplicationId();
    }

    @CheckForNull
    @Override
    public CleverCloudAgent getNode() {
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
