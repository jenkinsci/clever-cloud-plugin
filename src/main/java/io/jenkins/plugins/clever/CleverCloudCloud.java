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

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.clever.api.Application;
import io.jenkins.plugins.clever.api.WannabeApplication;
import io.jenkins.plugins.clever.api.WannabeEnv;
import io.swagger.client.api.ApplicationsApi;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.slaves.JnlpSlaveAgentProtocol;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloudCloud extends AbstractCloudImpl {


    private final String token;

    private final Secret secret;

    private final String credentialsId;

    private final List<AgentTemplate> templates;

    @DataBoundConstructor
    public CleverCloudCloud(String name, String token, Secret secret, String credentialsId, List<AgentTemplate> templates) {
        super(name, "10");
        this.token = token;
        this.secret = secret;
        this.credentialsId = credentialsId;

        this.templates = templates;
    }


    public String getToken() {
        return token;
    }

    public Secret getSecret() {
        return secret;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public List<AgentTemplate> getTemplates() {
        return templates;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {

        if (Jenkins.getInstance().isQuietingDown()) {
            return Collections.emptyList();
        }

        final AgentTemplate template = getTemplate(label);
        if (template == null) {
            return Collections.emptyList();
        }

        final List<PlannedNode> r = new ArrayList<>();

        for (int i = 0; i < excessWorkload; i++) {
            r.add(new PlannedNode(label, template));
        }

        for (PlannedNode plannedNode : r) {
            Computer.threadPoolForRemoting.submit(() -> {
                try {
                    CleverCloudAgent agent = _provision(label, template);
                    plannedNode.promise().complete(agent);
                } catch (Throwable t) {
                    plannedNode.promise().completeExceptionally(t);
                }
            });
        }

        return new ArrayList<NodeProvisioner.PlannedNode>(r);
    }

    /**
     * Provision a new Node on Clever-Cloud.
     */
    private CleverCloudAgent _provision(Label label, AgentTemplate template) throws Exception {

        final String agentName = UUID.randomUUID().toString();

        final ApplicationsApi api = new ApplicationsApi();

        WannabeApplication app = new WannabeApplication();
        app.setName("Jenkins agent "+ agentName);
        app.setMinInstances(1);
        app.setMaxInstances(1);
        app.setShutdownable(true);
        app.setSeparateBuild(false);
        app.setInstanceType("XS");

        String organisationId = "orga_3fab752b-3231-41b5-8b17-029e4689ba39";
        Application application = api.postOrganisationsIdApplications(organisationId, app);


        api.putOrganisationsIdApplicationsAppIdEnv(organisationId, application.getId(),
                new WannabeEnv().name("JENKINS_URL").value(JenkinsLocationConfiguration.get().getUrl()));
        api.putOrganisationsIdApplicationsAppIdEnv(organisationId, application.getId(),
                new WannabeEnv().name("JENKINS_AGENT_NAME").value(agentName));
        api.putOrganisationsIdApplicationsAppIdEnv(organisationId, application.getId(),
                new WannabeEnv().name("JENKINS_SECRET").value(JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(agentName)));

        final File tmp = Files.createTempDirectory("clever").toFile();
        final GitClient git = Git.with(TaskListener.NULL, new EnvVars(
                "GIT_AUTHOR_NAME=jenkin",
                "GIT_AUTHOR_EMAIL=jenkins@dev.null",
                "GIT_COMMITTER_NAME=jenkin",
                "GIT_COMMITTER_EMAIL=jenkins@dev.null"
        )).in(tmp).using("jgit").getClient();

        // Unfortunately we can't (yet) rely on a binary deployment API to just run jenkins/jnlp-slave
        FileUtils.writeStringToFile(new File(tmp, "Dockerfile"), "FROM jenkins/jnlp-slave");
        git.add("Dockerfile");
        git.commit("Deploy jenkins agent on clever cloud");

        final String remote = application.getDeployUrl();
        git.addCredentials(remote, getGitSSHCredentials());
        git.push().to(new URIish(remote)).execute();

        // TODO wait

        return null;      // TODO
    }

    @Override
    public boolean canProvision(Label label) {
        AgentTemplate template = getTemplate(label);
        return (template != null);
    }

    private AgentTemplate getTemplate(Label label) {
        for (AgentTemplate template : templates) {
            if (template.matches(label)) return template;
        }
        return null;
    }

    private SSHUserPrivateKey getGitSSHCredentials() {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(SSHUserPrivateKey.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Clever Cloud";
        }


        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter String credentialsId) {
            AccessControlled _context = (context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance());
            if (_context == null || !_context.hasPermission(Computer.CONFIGURE)) {
                return new StandardUsernameListBoxModel()
                        .includeCurrentValue(credentialsId);
            }
            return new StandardUsernameListBoxModel()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            context,
                            SSHUserPrivateKey.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }
    }
}
