/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloudbees.jenkins.plugins.sshagent.jna;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixServerSocket;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.apache.commons.io.FileUtils;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.common.AbstractAgentClient;
import org.apache.sshd.agent.local.AgentImpl;
import org.apache.sshd.common.util.OsUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;



/**
 * A server for an SSH Agent. Portions of this code were copied directly from Apache MINA's SSH implementation.
 */
public class AgentServer {

    private final SshAgent agent;
    private String authSocket;
    private Thread thread;
    private UnixSocketAddress address;
    private UnixServerSocketChannel channel;
    private UnixServerSocket socket;
    private Selector selector;
    private volatile boolean selectable = true;
    private final @CheckForNull File temp;

    public AgentServer(File temp) {
        this(new AgentImpl(), temp);
    }

    public AgentServer(SshAgent agent, File temp) {
        this.agent = agent;
        this.temp = temp;
    }

    public SshAgent getAgent() {
        return agent;
    }

    public String start() throws Exception {
        authSocket = createLocalSocketAddress();
        final File file = new File(authSocket);
        address = new UnixSocketAddress(file);
        channel = UnixServerSocketChannel.open();
        channel.configureBlocking(false);
        socket = channel.socket();
        socket.bind(address);
        selector = NativeSelectorProvider.getInstance().openSelector();

        channel.register(selector, SelectionKey.OP_ACCEPT, new SshAgentServerSocketHandler());


        final EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        permissions.add(PosixFilePermission.OWNER_READ);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file.toPath(), permissions);
        if (!file.exists()) {
            throw new IllegalStateException("failed to create " + authSocket + " of length " + authSocket.length() + " (check UNIX_PATH_MAX)");
        }

        thread = new Thread(new AgentSocketAcceptor(), "SSH Agent socket acceptor " +  authSocket);
        thread.setDaemon(true);
        thread.start();
        return authSocket;
    }

    final class AgentSocketAcceptor implements Runnable {
        public void run() {
            try {
                while (selectable) {
                    // The select() will be woke up if some new connection
                    // have occurred, or if the selector has been explicitly
                    // woke up
                    if (selector.select() > 0) {
                        Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();

                        while(selectedKeys.hasNext()) {
                            SelectionKey key = selectedKeys.next();
                            selectedKeys.remove();

                            if (key.isValid()) {
                                EventHandler processor = ((EventHandler) key.attachment());
                                processor.process(key);
                            }
                        }
                    }
                }

            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Error while waiting for events", ioe);
            } finally {
                if (selectable) {
                    LOGGER.log(Level.WARNING, "Unexpected death of thread {0}",
                            Thread.currentThread().getName());
                } else {
                    LOGGER.log(Level.FINE, "Thread {0} termination initiated by call to AgentServer.close()",
                            Thread.currentThread().getName());
                }
            }
        }
    }

    @SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", justification="createTempFile will fail anyway if there is a problem with mkdirs")
    private String createLocalSocketAddress() throws IOException {
        String name;
        if (temp != null) {
            temp.mkdirs();
        }
        if (OsUtils.isUNIX()) {
            File socket = File.createTempFile("ssh", "", temp);
            if (socket.getAbsolutePath().length() >= /*UNIX_PATH_MAX*/108) {
                LOGGER.log(Level.WARNING, "Cannot use {0} due to UNIX_PATH_MAX; falling back to system temp dir", socket);
                socket = File.createTempFile("ssh", "");
            }
            FileUtils.deleteQuietly(socket);
            name = socket.getAbsolutePath();
        } else {
            File socket = File.createTempFile("ssh", "", temp);
            FileUtils.deleteQuietly(socket);
            name = "\\\\.\\pipe\\" + socket.getName();
        }
        return name;
    }

    public void close() {
        selectable = false;
        selector.wakeup();

        // forcibly close remaining sockets
        for (SelectionKey key : selector.keys()) {
            if (key != null) {
                safelyClose(key.channel());
            }
        }

        safelyClose(selector);
        safelyClose(agent);
        safelyClose(channel);
        if (authSocket != null) {
            FileUtils.deleteQuietly(new File(authSocket));
        }
    }

    interface EventHandler {
        void process(SelectionKey key) throws IOException;
    }

    final class SshAgentServerSocketHandler implements EventHandler {
        public final void process(SelectionKey key) throws IOException {
            try {
                UnixSocketChannel clientChannel = channel.accept();
                clientChannel.configureBlocking(false);
                clientChannel.register(selector, SelectionKey.OP_READ, new SshAgentSessionSocketHandler(clientChannel));
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING, "failed to accept new connection", ex);
                safelyClose(channel);
                throw ex;
            }
        }
    }

    final class SshAgentSessionSocketHandler extends AbstractAgentClient implements EventHandler {

        public static final byte SSH_AGENTC_REQUEST_RSA_IDENTITIES=1;
        public static final byte SSH_AGENT_RSA_IDENTITIES_ANSWER=2;

        private final UnixSocketChannel sessionChannel;

        public SshAgentSessionSocketHandler(UnixSocketChannel sessionChannel) {
            super(agent);
            this.sessionChannel = sessionChannel;
        }

        public void process(SelectionKey key) {
            try {
                ByteBuffer buf = ByteBuffer.allocate(1024);
                int result;
                while (0 < (result = sessionChannel.read(buf))) {
                    buf.flip();
                    messageReceived(new ByteArrayBuffer(buf.array(), buf.position(), buf.remaining()));
                    if (result == 1024) {
                        buf.rewind();
                    } else {
                        return;
                    }
                }

                if (result == -1) {
                    // EOF => remote closed the connection, cancel the selection key and close the channel.
                    key.cancel();
                    sessionChannel.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.INFO, "Could not write response to socket", e);
                key.cancel();
                safelyClose(sessionChannel);
            }
        }

        @Override
        protected void reply(Buffer buf) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(buf.array(), buf.rpos(), buf.available());
            int result = sessionChannel.write(b);
            if (result < 0) {
                throw new IOException("Could not write response to socket");
            }
        }

        @Override
        protected void process(int cmd, Buffer req, Buffer rep) throws Exception {
            switch (cmd) {
                case SSH_AGENTC_REQUEST_RSA_IDENTITIES:
                    // stop causing ssh-add -l to log errors
                    rep.putByte(SSH_AGENT_RSA_IDENTITIES_ANSWER);
                    rep.putInt(0);
                    break;

                default:
                    super.process(cmd, req, rep);
                    break;
            }
        }
    }

    private static void safelyClose(Closeable channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOGGER.log(Level.INFO, "Error while closing resource", e);
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(AgentServer.class.getName());
}
