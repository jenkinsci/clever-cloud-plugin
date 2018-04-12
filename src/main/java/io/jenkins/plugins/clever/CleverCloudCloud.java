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
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.logging.HttpLoggingInterceptor;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.clever.api.Application;
import io.jenkins.plugins.clever.api.ApplicationsApi;
import io.jenkins.plugins.clever.api.Instance;
import io.jenkins.plugins.clever.api.ProductsApi;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverCloudCloud extends AbstractCloudImpl {


    // we use the same consumer key as clever CLI
    // https://github.com/CleverCloud/clever-tools/blob/master/src/models/configuration.js
    private final String CONSUMER_KEY = "T5nFjKeHH4AIlEveuGhB5S3xg8T19e";
    private final String CONSUMER_SECRET = "MgVMqTr6fWlf2M0tkC2MXOnhfqBWDT";

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

    private static final String organisationId = "orga_3fab752b-3231-41b5-8b17-029e4689ba39";


    /**
     * Provision a new Node on Clever-Cloud.
     */
    private CleverCloudAgent _provision(Label label, AgentTemplate template) throws Exception {

        final JenkinsLocationConfiguration locationConfiguration = JenkinsLocationConfiguration.get();
        if (locationConfiguration == null) throw new IOException("Jenkins URL not set");

        final String agentName = UUID.randomUUID().toString();

        final ApiClient c = getApiClient();
        final ApplicationsApi api = new ApplicationsApi(c);
        final ProductsApi products = new ProductsApi(c);

        final Optional<Instance> docker = products.getProductsInstances("").stream()
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
        app.setMinFlavor("XS"); // Use flavor as jenkins label ?
        app.setMaxFlavor("XS");
        app.setZone("par"); // TODO
        app.setDeploy("git"); // TODO waiting for a binary deploy API so we can just deploy 'jenkins/jnlp-slave' without a fake Dockerfile"

        Application application = api.postOrganisationsIdApplications(organisationId, app);

        Map<String, String> env = new HashMap<>();
        env.put("JENKINS_URL",locationConfiguration.getUrl());
        env.put("JENKINS_AGENT_NAME", agentName);
        env.put("JENKINS_SECRET", JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(agentName));

        api.putOrganisationsIdApplicationsAppIdEnv(organisationId, application.getId(), env);

        dockerRun(application, "jenkins/jnlp-slave");

        return new CleverCloudAgent(this.name, agentName, application.getId(), "/home/jenkins", label.toString());
    }

    public void terminate(CleverCloudAgent agent) throws IOException {
        final ApiClient c = getApiClient();
        final ApplicationsApi api = new ApplicationsApi(c);
        final String id = agent.getApplicationId();
        try {
            api.deleteOrganisationsIdApplicationsAppId(organisationId, id);
        } catch (ApiException e) {
            throw new IOException("Failed to delete Application "+id, e);
        }
    }

    private ApiClient getApiClient() {
        final ApiClient c = new ApiClient();
        OkHttpOAuthConsumer consumer = new OkHttpOAuthConsumer(CONSUMER_KEY, CONSUMER_SECRET);
        consumer.setTokenWithSecret(token, Secret.toString(secret));

        final List<Interceptor> interceptors = c.getHttpClient().interceptors();
        interceptors.add(new SigningInterceptor(consumer));
        final HttpLoggingInterceptor logging = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
            @Override public void log(String message) {
                System.out.println(message);
            }
        });
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        interceptors.add(logging);
        return c;
    }



    private void dockerRun(Application application, String dockerImage) throws IOException, InterruptedException, URISyntaxException {
        final File tmp = Files.createTempDirectory("clever").toFile();
        final GitClient git = Git.with(TaskListener.NULL, new EnvVars(
                "GIT_AUTHOR_NAME=jenkin",
                "GIT_AUTHOR_EMAIL=jenkins@dev.null",
                "GIT_COMMITTER_NAME=jenkin",
                "GIT_COMMITTER_EMAIL=jenkins@dev.null"
        )).in(tmp).using("jgit").getClient();
        git.init();

        // Unfortunately we can't (yet) rely on a binary deployment API to just run jenkins/jnlp-slave
        FileUtils.writeStringToFile(new File(tmp, "Dockerfile"), "FROM "+dockerImage);
        git.add("Dockerfile");
        git.commit("Deploy jenkins agent on clever cloud");

        final String remote = application.getDeployUrl();
        git.addCredentials(remote, getGitSSHCredentials());
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
