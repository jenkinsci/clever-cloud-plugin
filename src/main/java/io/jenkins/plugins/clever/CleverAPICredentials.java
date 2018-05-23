package io.jenkins.plugins.clever;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.jenkins.plugins.clever.api.AllApi;
import io.jenkins.plugins.clever.api.OrganisationsApi;
import io.jenkins.plugins.clever.api.User;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class CleverAPICredentials extends BaseStandardCredentials implements UsernamePasswordCredentials, StandardUsernameCredentials {

    private final String token;
    private final Secret secret;
    private final Secret consumerKey;
    private final Secret consumerSecret;

    // if not set we use the same consumer key as clever CLI
    // https://github.com/CleverCloud/clever-tools/blob/master/src/models/configuration.js
    private static final String CLI_CONSUMER_KEY = "T5nFjKeHH4AIlEveuGhB5S3xg8T19e";
    private static final String CLI_CONSUMER_SECRET = "MgVMqTr6fWlf2M0tkC2MXOnhfqBWDT";


    @DataBoundConstructor
    public CleverAPICredentials(String id, String description, String token, String secret, String consumerKey, String consumerSecret) {
        super(id, description);
        this.token = token;
        this.secret = Secret.fromString(secret);
        consumerKey = StringUtils.trimToNull(consumerKey);
        this.consumerKey = Secret.fromString(consumerKey != null ? consumerKey : CLI_CONSUMER_KEY);
        consumerSecret = StringUtils.trimToNull(consumerSecret);
        this.consumerSecret = Secret.fromString(consumerSecret != null ? consumerSecret : CLI_CONSUMER_SECRET);
    }

    public String getToken() {
        return token;
    }

    @NonNull
    @Override
    public String getUsername() {
        return token;
    }

    public Secret getSecret() {
        return secret;
    }

    @NonNull
    @Override
    public Secret getPassword() {
        return secret;
    }

    public Secret getConsumerKey() {
        return consumerKey;
    }

    public Secret getConsumerSecret() {
        return consumerSecret;
    }

    @Extension
    public static class DescriptorImpl extends CredentialsDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Clever Cloud API credentials";
        }

        public FormValidation doTestCredentials(@QueryParameter("token") String token,
                                                @QueryParameter("secret") String secret,
                                                @QueryParameter("consumerKey") String consumerKey,
                                                @QueryParameter("consumerSecret") String consumerSecret) {
            final AllApi api = new AllApi(CleverCloud.getApiClient(
                    new CleverAPICredentials(null, null, token, secret, consumerKey, consumerSecret)));
            try {
                final User self = api.getSelf();
                return FormValidation.ok("👍 " + self.getName());
            } catch (ApiException e) {
                return FormValidation.error("Invalid credentials", e);
            }

        }
    }
}
