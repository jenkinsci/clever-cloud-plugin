package io.jenkins.plugins.clever;

import com.sun.net.httpserver.HttpServer;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerListener;
import jenkins.security.MasterToSlaveCallable;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;

/**
 * As computer get connected, run a http server on node so clever cloud will detect a successful deployment
 * (as there's no other option (yet) to detect a successful deployment.
 * 
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
@Extension
public class AgentListener extends ComputerListener {

    @Override
    public void preOnline(Computer c, Channel channel, FilePath root, TaskListener listener) throws IOException, InterruptedException {

        channel.call(new Http8080Server());
    }

    private static final class Http8080Server extends MasterToSlaveCallable<Object, IOException> implements Serializable {

        static final long serialVersionUID = 1L;

        @Override
        public Object call() throws IOException {
            final HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 1);
            server.start();
            return null;
        }
    }
}
