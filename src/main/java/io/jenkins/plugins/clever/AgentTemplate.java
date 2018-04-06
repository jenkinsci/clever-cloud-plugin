package io.jenkins.plugins.clever;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class AgentTemplate extends AbstractDescribableImpl<AgentTemplate> {

    private final String displayName;

    private final String label;

    @DataBoundConstructor
    public AgentTemplate(String displayName, String label) {
        this.displayName = displayName;
        this.label = label;
    }


    public String getDisplayName() {
        return displayName;
    }

    public final String getLabel() {
        return label;
    }

    public boolean matches(Label l) {
        return l.matches(Label.get(label).listAtoms());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AgentTemplate> {

    }
}
