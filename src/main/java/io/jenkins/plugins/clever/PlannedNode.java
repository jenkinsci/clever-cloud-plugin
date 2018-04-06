package io.jenkins.plugins.clever;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.NodeProvisioner;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link Node} planned for allocation.
 * TODO : Keep track of allocation status and progress
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class PlannedNode extends NodeProvisioner.PlannedNode {

    /**
     * Label for which this slave is allocated.
     */
    private final Label label;


    public PlannedNode(Label label, AgentTemplate template) {
        super(template.getDisplayName(), new CompletableFuture<>(), 1);
        this.label = label;
    }

    /* package */ CompletableFuture<Node> promise() {
        return (CompletableFuture<Node>) super.future;
    }

}
