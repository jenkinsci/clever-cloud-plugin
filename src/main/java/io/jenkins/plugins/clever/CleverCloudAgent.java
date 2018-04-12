/*
 * The MIT License
 *
 * Copyright 2018 ndeloof.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.clever;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Collections;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloudAgent extends AbstractCloudSlave implements EphemeralNode {

    private final static RetentionStrategy STRATEGY = new CleverCloudAgentRetentionStrategy();

    private final String cloud;
    private final String applicationId;

    public CleverCloudAgent(String cloud, String name, String applicationId, String remoteFS, String labelString) throws Descriptor.FormException, IOException {
        super(name, "jenkins agent on clever cloud", remoteFS, 1, Mode.EXCLUSIVE, labelString, new JNLPLauncher(), STRATEGY, Collections.emptyList());
        this.cloud = cloud;
        this.applicationId = applicationId;
    }

    private CleverCloudCloud getCloud() {
        for (Cloud c : Jenkins.getInstance().clouds) {
            if (c.name.equals(cloud)) {
                return (CleverCloudCloud) c;
            }
        }
        throw new IllegalStateException("cloud "+cloud+" does not exists.");
    }

    public String getApplicationId() {
        return applicationId;
    }

    @Override
    public AbstractCloudComputer createComputer() {
        return null;
    }

    @Override
    protected void _terminate(TaskListener taskListener) throws IOException, InterruptedException {
        getCloud().terminate(this);
    }

    @Override
    public Node asNode() {
        return this;
    }
}
