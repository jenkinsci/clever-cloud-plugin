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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.logging.HttpLoggingInterceptor;
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
import io.jenkins.plugins.clever.api.AllApi;
import io.jenkins.plugins.clever.api.Application;
import io.jenkins.plugins.clever.api.Instance;
import io.jenkins.plugins.clever.api.Organisation;
import io.jenkins.plugins.clever.api.User;
import io.jenkins.plugins.clever.api.WannabeApplication;
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
import se.akerfeldt.okhttp.signpost.OkHttpOAuthConsumer;
import se.akerfeldt.okhttp.signpost.SigningInterceptor;

import javax.annotation.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloud extends AbstractCloudImpl {


    private String credentialsId;

    private final String organisationId;

    private final List<AgentTemplate> templates;

    @DataBoundConstructor
    public CleverCloud(String name, String credentialsId, String organisationId, List<AgentTemplate> templates) {
        super(name, "10");
        this.credentialsId = credentialsId;
        this.organisationId = organisationId;
        this.templates = templates;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getOrganisationId() {
        return organisationId;
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
                    CleverAgent agent = _provision(label, template);
                    plannedNode.promise().complete(agent);
                } catch (Throwable t) {
                    plannedNode.promise().completeExceptionally(t);
                }
            });
        }

        return new ArrayList<NodeProvisioner.PlannedNode>(r);
    }

    @CheckForNull
    private static CleverAPICredentials getAPICredentials(String credentialsId) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(CleverAPICredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    /**
     * Provision a new Node on Clever-Cloud.
     */
    private CleverAgent _provision(Label label, AgentTemplate template) throws Exception {

        final JenkinsLocationConfiguration locationConfiguration = JenkinsLocationConfiguration.get();
        if (locationConfiguration == null) throw new IOException("Jenkins URL not set");

        final String agentName = UUID.randomUUID().toString();

        final ApiClient c = getApiClient(getAPICredentials(credentialsId));
        final AllApi api = new AllApi(c);

        final Optional<Instance> docker = api.getProductsInstances("").stream()
                .filter((it) -> it.isEnabled() && it.getType().equals("docker"))
                .findFirst();

        if (!docker.isPresent()) throw new IOException("No 'docker' instance available");
        final Instance instance = docker.get();

        WannabeApplication app = new WannabeApplication();
        app.setName("Jenkins agent "+ agentName);
        app.setMinInstances(1);
        app.setMaxInstances(1);
        app.setShutdownable(true);
        app.setSeparateBuild(false);
        app.setInstanceType(instance.getType());
        app.setInstanceVariant(instance.getVariant().getId());
        app.setInstanceVersion(instance.getVersion());
        app.setMinFlavor(template.getScaler());
        app.setMaxFlavor(template.getScaler());
        app.setZone("par"); // TODO
        app.setDeploy("git"); // TODO waiting for a binary deploy API so we can just deploy 'jenkins/jnlp-slave' without a fake Dockerfile"

        Application application = api.postOrganisationsIdApplications(organisationId, app);
        final CleverAgent agent = new CleverAgent(this.name, agentName, application.getId(), "/home/jenkins", label.toString());

        // Register agent so it becomes a valid JNLP target
        Jenkins.getInstance().addNode(agent);

        try {
            Map<String, String> env = new HashMap<>();
            env.put("JENKINS_URL", locationConfiguration.getUrl());
            env.put("JENKINS_AGENT_NAME", agentName);
            env.put("JENKINS_SECRET", JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(agentName));
            env.put("CC_MOUNT_DOCKER_SOCKET", "true");

            api.putOrganisationsIdApplicationsAppIdEnv(organisationId, application.getId(), env);

            dockerRun(application, template.getDockerImage());
        } catch (IOException e) {
            // Something went wrong, ensure we remove clever-cloud application
            api.deleteOrganisationsIdApplicationsAppId(organisationId, application.getId());
            throw e;
        }

        return agent;
    }

    public void terminate(CleverAgent agent) throws IOException {
        final ApiClient c = getApiClient(getAPICredentials(credentialsId));
        final AllApi api = new AllApi(c);
        final String id = agent.getApplicationId();
        try {
                api.deleteOrganisationsIdApplicationsAppId(organisationId, id);
            } catch (ApiException e) {
                throw new IOException("Failed to delete Application "+id, e);
            }
    }

    private static boolean debug = Boolean.getBoolean(CleverCloud.class.getName()+".debug");

    static ApiClient getApiClient(CleverAPICredentials credentials) {
        final ApiClient c = new ApiClient();
        final List<Interceptor> interceptors = c.getHttpClient().interceptors();
        if (credentials != null) {
            OkHttpOAuthConsumer consumer = new OkHttpOAuthConsumer(credentials.getConsumerKey().getPlainText(), credentials.getConsumerSecret().getPlainText());
            consumer.setTokenWithSecret(credentials.getToken(), credentials.getSecret().getPlainText());
            interceptors.add(new SigningInterceptor(consumer));
        }

        if (debug) {
            final HttpLoggingInterceptor logging = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
                @Override
                public void log(String message) {
                    System.out.println(message);
                }
            });
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            interceptors.add(logging);
        }
        return c;
    }


    /**
     * Use clever API to emulate a remote <code>docker run</code> command.
     * Which require to create a fake Dockerfile with a single <code>FROM</code> command, commit to a fake single-commit
     * git repository and git-push to clever deploy API endpoint.
     */
    private void dockerRun(Application application, String dockerImage) throws IOException, InterruptedException, URISyntaxException {
        final File tmp = Files.createTempDirectory("clever").toFile();
        final GitClient git = Git.with(TaskListener.NULL, new EnvVars(
                "GIT_AUTHOR_NAME=jenkins",
                "GIT_AUTHOR_EMAIL=jenkins@dev.null",
                "GIT_COMMITTER_NAME=jenkins",
                "GIT_COMMITTER_EMAIL=jenkins@dev.null"
        )).in(tmp).using("jgit").getClient();
        git.init();

        // Unfortunately we can't (yet) rely on a binary deployment API to just run jenkins/jnlp-slave
        FileUtils.writeStringToFile(new File(tmp, "Dockerfile"), "FROM "+dockerImage);
        git.add("Dockerfile");
        git.commit("Deploy jenkins agent on clever cloud");

        final String remote = application.getDeployment().getHttpUrl();
        git.addCredentials(remote, getAPICredentials(credentialsId));
        git.push().to(new URIish(remote)).execute();
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
            return new StandardUsernameListBoxModel().includeAs(ACL.SYSTEM, context, CleverAPICredentials.class);
        }

        public ListBoxModel doFillOrganisationIdItems(@QueryParameter("credentialsId") String credentialsId) {

            if (credentialsId.length() == 0) {
                // When form render, credentialsId is only set once doFillCredentialsIdItems populates the select box
                // but doFillOrganisationIdItems has already be invoked in the meantime with empty string
                final List<CleverAPICredentials> candidates =
                    CredentialsProvider.lookupCredentials(CleverAPICredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList());
                if (candidates.size() > 0) {
                    credentialsId = candidates.get(0).getId();
                }
            }

            final AllApi api = new AllApi(getApiClient(getAPICredentials(credentialsId)));
            ListBoxModel model = new ListBoxModel();
            try {
                final User self = api.getSelf();
                final List<Organisation> organisations = api.getOrganisations(self.getId()); // can't set null ?
                for (Organisation me : organisations) {
                    model.add(me.getName(), me.getId());
                }
            } catch (ApiException e) {
                // TODO report auth error on web UI;
            }
            return model;
        }
    }
}
