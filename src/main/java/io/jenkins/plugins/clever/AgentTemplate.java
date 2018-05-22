package io.jenkins.plugins.clever;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class AgentTemplate extends AbstractDescribableImpl<AgentTemplate> {

    private final String displayName;

    private final String label;

    private String scaler;

    private String dockerImage;

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

    public String getScaler() {
        return scaler != null ? scaler : "XS";
    }

    @DataBoundSetter
    public void setScaler(String scaler) {
        this.scaler = scaler;
    }

    public String getDockerImage() {
        return dockerImage != null ? dockerImage : "jenkins/jnlp-slave";
    }

    @DataBoundSetter
    public void setDockerImage(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    public boolean matches(Label l) {
        return l.matches(Label.get(label).listAtoms());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AgentTemplate> {

        public ListBoxModel doFillScalerItems() {
            final ListBoxModel options = new ListBoxModel();
            options.add("pico");
            options.add("nano");
            options.add("XS");
            options.add("S");
            options.add("M");
            options.add("L");
            options.add("XL");
            return options;
        }
    }
}
