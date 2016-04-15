/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright 2009 Jive Software.
 *
 * All rights reserved. Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.smack;

import android.util.Log;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import org.jivesoftware.smack.compression.JzlibInputOutputStream;
import org.jivesoftware.smack.compression.XMPPInputOutputStream;
import org.jivesoftware.smack.compression.Java7ZlibInputOutputStream;
import org.jivesoftware.smack.debugger.SmackDebugger;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

/**
 * The abstract Connection class provides an interface for connections to a
 * XMPP server and implements shared methods which are used by the
 * different types of connections (e.g. XMPPConnection or BoshConnection).
 * 
 * To create a connection to a XMPP server a simple usage of this API might
 * look like the following:
 * <pre>
 * // Create a connection to the igniterealtime.org XMPP server.
 * Connection con = new XMPPConnection("igniterealtime.org");
 * // Connect to the server
 * con.connect();
 * // Most servers require you to login before performing other tasks.
 * con.login("jsmith", "mypass");
 * // Start a new conversation with John Doe and send him a message.
 * Chat chat = connection.getChatManager().createChat("jdoe@igniterealtime.org"</font>, new MessageListener() {
 * <p/>
 *     public void processMessage(Chat chat, Message message) {
 *         // Print out any messages we get back to standard out.
 *         System.out.println(<font color="green">"Received message: "</font> + message);
 *     }
 * });
 * chat.sendMessage(<font color="green">"Howdy!"</font>);
 * // Disconnect from the server
 * con.disconnect();
 * </pre>
 * <p/>
 * Connections can be reused between connections. This means that an Connection
 * may be connected, disconnected and then connected again. Listeners of the Connection
 * will be retained accross connections.<p>
 * <p/>
 * If a connected Connection gets disconnected abruptly then it will try to reconnect
 * again. To stop the reconnection process, use {@link #disconnect()}. Once stopped
 * you can use {@link #connect()} to manually connect to the server.
 * 
 * @see XMPPConnection
 * @author Matt Tucker
 * @author Guenther Niess
 */
public abstract class Connection {

    /** 
     * Counter to uniquely identify connections that are created.
     */
    private final static AtomicInteger connectionCounter = new AtomicInteger(0);

    /**
     * A set of listeners which will be invoked if a new connection is created.
     */
    private final static Set<ConnectionCreationListener> connectionEstablishedListeners =
            new CopyOnWriteArraySet<ConnectionCreationListener>();

    protected final static List<XMPPInputOutputStream> compressionHandlers = new ArrayList<XMPPInputOutputStream>(2);

    /**
     * Value that indicates whether debugging is enabled. When enabled, a debug
     * window will apear for each new connection that will contain the following
     * information:<ul>
     * <li> Client Traffic -- raw XML traffic generated by Smack and sent to the server.
     * <li> Server Traffic -- raw XML traffic sent by the server to the client.
     * <li> Interpreted Packets -- shows XML packets from the server as parsed by Smack.
     * </ul>
     * <p/>
     * Debugging can be enabled by setting this field to true, or by setting the Java system
     * property <tt>smack.debugEnabled</tt> to true. The system property can be set on the
     * command line such as "java SomeApp -Dsmack.debugEnabled=true".
     */
    public static boolean DEBUG_ENABLED = false;

    static {
        // Use try block since we may not have permission to get a system
        // property (for example, when an applet).
        try {
            DEBUG_ENABLED = Boolean.getBoolean("smack.debugEnabled");
        }
        catch (Exception e) {
            // Ignore.
        }
        // Ensure the SmackConfiguration class is loaded by calling a method in it.
        SmackConfiguration.getVersion();
        // Add the Java7 compression handler first, since it's preferred
        compressionHandlers.add(new Java7ZlibInputOutputStream());
        // If we don't have access to the Java7 API use the JZlib compression handler
        compressionHandlers.add(new JzlibInputOutputStream());
    }

    /**
     * A collection of ConnectionListeners which listen for connection closing
     * and reconnection events.
     */
    protected final Collection<ConnectionListener> connectionListeners =
            new CopyOnWriteArrayList<ConnectionListener>();

    /**
     * A collection of PacketCollectors which collects packets for a specified filter
     * and perform blocking and polling operations on the result queue.
     */
    protected final Collection<PacketCollector> collectors = new ConcurrentLinkedQueue<PacketCollector>();

    /**
     * List of PacketListeners that will be notified when a new packet was received.
     */
    protected final Map<PacketListener, ListenerWrapper> recvListeners =
            new ConcurrentHashMap<PacketListener, ListenerWrapper>();

    /**
     * List of PacketListeners that will be notified when a new packet was sent.
     */
    protected final Map<PacketListener, ListenerWrapper> sendListeners =
            new ConcurrentHashMap<PacketListener, ListenerWrapper>();

    /**
     * List of PacketInterceptors that will be notified when a new packet is about to be
     * sent to the server. These interceptors may modify the packet before it is being
     * actually sent to the server.
     */
    protected final Map<PacketInterceptor, InterceptorWrapper> interceptors =
            new ConcurrentHashMap<PacketInterceptor, InterceptorWrapper>();

    /**
     * The AccountManager allows creation and management of accounts on an XMPP server.
     */
    private AccountManager accountManager = null;

    /**
     * The ChatManager keeps track of references to all current chats.
     */
    protected ChatManager chatManager = null;

    /**
     * The SmackDebugger allows to log and debug XML traffic.
     */
    protected SmackDebugger debugger = null;

    /**
     * The Reader which is used for the {@see debugger}.
     */
    protected Reader reader;

    /**
     * The Writer which is used for the {@see debugger}.
     */
    protected Writer writer;
    
    /**
     * The permanent storage for the roster
     */
    protected RosterStorage rosterStorage;


    /**
     * The SASLAuthentication manager that is responsible for authenticating with the server.
     */
    protected SASLAuthentication saslAuthentication = new SASLAuthentication(this);

    /**
     * A number to uniquely identify connections that are created. This is distinct from the
     * connection ID, which is a value sent by the server once a connection is made.
     */
    protected final int connectionCounterValue = connectionCounter.getAndIncrement();

    /**
     * Holds the initial configuration used while creating the connection.
     */
    protected final ConnectionConfiguration config;

    /**
     * Holds the Caps Node information for the used XMPP service (i.e. the XMPP server)
     */
    private String serviceCapsNode;

    protected XMPPInputOutputStream compressionHandler;

    /**
     * Create a new Connection to a XMPP server.
     * 
     * @param configuration The configuration which is used to establish the connection.
     */
    protected Connection(ConnectionConfiguration configuration) {
        config = configuration;
    }

    /**
     * Returns the configuration used to connect to the server.
     * 
     * @return the configuration used to connect to the server.
     */
    protected ConnectionConfiguration getConfiguration() {
        return config;
    }

    /**
     * Returns the name of the service provided by the XMPP server for this connection.
     * This is also called XMPP domain of the connected server. After
     * authenticating with the server the returned value may be different.
     * 
     * @return the name of the service provided by the XMPP server.
     */
    public String getServiceName() {
        return config.getServiceName();
    }

    /**
     * Returns the host name of the server where the XMPP server is running. This would be the
     * IP address of the server or a name that may be resolved by a DNS server.
     * 
     * @return the host name of the server where the XMPP server is running.
     */
    public String getHost() {
        return config.getHost();
    }

    /**
     * Returns the port number of the XMPP server for this connection. The default port
     * for normal connections is 5222. The default port for SSL connections is 5223.
     * 
     * @return the port number of the XMPP server.
     */
    public int getPort() {
        return config.getPort();
    }

    /**
     * Returns the full XMPP address of the user that is logged in to the connection or
     * <tt>null</tt> if not logged in yet. An XMPP address is in the form
     * username@server/resource.
     * 
     * @return the full XMPP address of the user logged in.
     */
    public abstract String getUser();

    /**
     * Returns the connection ID for this connection, which is the value set by the server
     * when opening a XMPP stream. If the server does not set a connection ID, this value
     * will be null. This value will be <tt>null</tt> if not connected to the server.
     * 
     * @return the ID of this connection returned from the XMPP server or <tt>null</tt> if
     *      not connected to the server.
     */
    public abstract String getConnectionID();

    /**
     * Returns true if currently connected to the XMPP server.
     * 
     * @return true if connected.
     */
    public abstract boolean isConnected();

    /**
     * Returns true if currently authenticated by successfully calling the login method.
     * 
     * @return true if authenticated.
     */
    public abstract boolean isAuthenticated();

    /**
     * Returns true if currently authenticated anonymously.
     * 
     * @return true if authenticated anonymously.
     */
    public abstract boolean isAnonymous();

    /**
     * Returns true if the connection to the server has successfully negotiated encryption. 
     * 
     * @return true if a secure connection to the server.
     */
    public abstract boolean isSecureConnection();

    /**
     * Returns if the reconnection mechanism is allowed to be used. By default
     * reconnection is allowed.
     * 
     * @return true if the reconnection mechanism is allowed to be used.
     */
    protected boolean isReconnectionAllowed() {
        return config.isReconnectionAllowed();
    }

    /**
     * Returns true if network traffic is being compressed. When using stream compression network
     * traffic can be reduced up to 90%. Therefore, stream compression is ideal when using a slow
     * speed network connection. However, the server will need to use more CPU time in order to
     * un/compress network data so under high load the server performance might be affected.
     * 
     * @return true if network traffic is being compressed.
     */
    public abstract boolean isUsingCompression();

    /**
     * Establishes a connection to the XMPP server and performs an automatic login
     * only if the previous connection state was logged (authenticated). It basically
     * creates and maintains a connection to the server.<p>
     * <p/>
     * Listeners will be preserved from a previous connection if the reconnection
     * occurs after an abrupt termination.
     * 
     * @throws XMPPException if an error occurs while trying to establish the connection.
     */
    public abstract void connect() throws XMPPException;

    /**
     * Logs in to the server using the strongest authentication mode supported by
     * the server, then sets presence to available. If the server supports SASL authentication 
     * then the user will be authenticated using SASL if not Non-SASL authentication will 
     * be tried. If more than five seconds (default timeout) elapses in each step of the 
     * authentication process without a response from the server, or if an error occurs, a 
     * XMPPException will be thrown.<p>
     * 
     * Before logging in (i.e. authenticate) to the server the connection must be connected.
     * 
     * It is possible to log in without sending an initial available presence by using
     * {@link ConnectionConfiguration#setSendPresence(boolean)}. If this connection is
     * not interested in loading its roster upon login then use
     * {@link ConnectionConfiguration#setRosterLoadedAtLogin(boolean)}.
     * Finally, if you want to not pass a password and instead use a more advanced mechanism
     * while using SASL then you may be interested in using
     * {@link ConnectionConfiguration#setCallbackHandler(javax.security.auth.callback.CallbackHandler)}.
     * For more advanced login settings see {@link ConnectionConfiguration}.
     * 
     * @param username the username.
     * @param password the password or <tt>null</tt> if using a CallbackHandler.
     * @throws XMPPException if an error occurs.
     */
    public void login(String username, String password) throws XMPPException {
        login(username, password, "Smack");
    }

    /**
     * Logs in to the server using the strongest authentication mode supported by
     * the server, then sets presence to available. If the server supports SASL authentication 
     * then the user will be authenticated using SASL if not Non-SASL authentication will 
     * be tried. If more than five seconds (default timeout) elapses in each step of the 
     * authentication process without a response from the server, or if an error occurs, a 
     * XMPPException will be thrown.<p>
     * 
     * Before logging in (i.e. authenticate) to the server the connection must be connected.
     * 
     * It is possible to log in without sending an initial available presence by using
     * {@link ConnectionConfiguration#setSendPresence(boolean)}. If this connection is
     * not interested in loading its roster upon login then use
     * {@link ConnectionConfiguration#setRosterLoadedAtLogin(boolean)}.
     * Finally, if you want to not pass a password and instead use a more advanced mechanism
     * while using SASL then you may be interested in using
     * {@link ConnectionConfiguration#setCallbackHandler(javax.security.auth.callback.CallbackHandler)}.
     * For more advanced login settings see {@link ConnectionConfiguration}.
     * 
     * @param username the username.
     * @param password the password or <tt>null</tt> if using a CallbackHandler.
     * @param resource the resource.
     * @throws XMPPException if an error occurs.
     * @throws IllegalStateException if not connected to the server, or already logged in
     *      to the serrver.
     */
    public abstract void login(String username, String password, String resource) throws XMPPException;

    /**
     * Logs in to the server anonymously. Very few servers are configured to support anonymous
     * authentication, so it's fairly likely logging in anonymously will fail. If anonymous login
     * does succeed, your XMPP address will likely be in the form "123ABC@server/789XYZ" or
     * "server/123ABC" (where "123ABC" and "789XYZ" is a random value generated by the server).
     * 
     * @throws XMPPException if an error occurs or anonymous logins are not supported by the server.
     * @throws IllegalStateException if not connected to the server, or already logged in
     *      to the serrver.
     */
    public abstract void loginAnonymously() throws XMPPException;

    /**
     * Sends the specified packet to the server.
     * 
     * @param packet the packet to send.
     */
    public abstract void sendPacket(Packet packet);

    /**
     * Returns an account manager instance for this connection.
     * 
     * @return an account manager for this connection.
     */
    public AccountManager getAccountManager() {
        if (accountManager == null) {
            accountManager = new AccountManager(this);
        }
        return accountManager;
    }

    /**
     * Returns a chat manager instance for this connection. The ChatManager manages all incoming and
     * outgoing chats on the current connection.
     * 
     * @return a chat manager instance for this connection.
     */
    public synchronized ChatManager getChatManager() {
        if (this.chatManager == null) {
            this.chatManager = new ChatManager(this);
        }
        return this.chatManager;
    }

    /**
     * Returns the roster for the user.
     * <p>
     * This method will never return <code>null</code>, instead if the user has not yet logged into
     * the server or is logged in anonymously all modifying methods of the returned roster object
     * like {@link Roster#createEntry(String, String, String[])},
     * {@link Roster#removeEntry(RosterEntry)} , etc. except adding or removing
     * {@link RosterListener}s will throw an IllegalStateException.
     * 
     * @return the user's roster.
     */
    public abstract Roster getRoster();
    
    /**
     * Set the store for the roster of this connection. If you set the roster storage
     * of a connection you enable support for XEP-0237 (RosterVersioning)
     * @param store the store used for roster versioning
     * @throws IllegalStateException if you add a roster store when roster is initializied
     */
    public abstract void setRosterStorage(RosterStorage storage) throws IllegalStateException;
    
    /**
     * Returns the SASLAuthentication manager that is responsible for authenticating with
     * the server.
     * 
     * @return the SASLAuthentication manager that is responsible for authenticating with
     *         the server.
     */
    public SASLAuthentication getSASLAuthentication() {
        return saslAuthentication;
    }

    /**
     * Closes the connection by setting presence to unavailable then closing the connection to
     * the XMPP server. The Connection can still be used for connecting to the server
     * again.<p>
     * <p/>
     * This method cleans up all resources used by the connection. Therefore, the roster,
     * listeners and other stateful objects cannot be re-used by simply calling connect()
     * on this connection again. This is unlike the behavior during unexpected disconnects
     * (and subsequent connections). In that case, all state is preserved to allow for
     * more seamless error recovery.
     */
    public void disconnect() {
        disconnect(new Presence(Presence.Type.unavailable));
    }

    /**
     * Closes the connection. A custom unavailable presence is sent to the server, followed
     * by closing the stream. The Connection can still be used for connecting to the server
     * again. A custom unavilable presence is useful for communicating offline presence
     * information such as "On vacation". Typically, just the status text of the presence
     * packet is set with online information, but most XMPP servers will deliver the full
     * presence packet with whatever data is set.<p>
     * <p/>
     * This method cleans up all resources used by the connection. Therefore, the roster,
     * listeners and other stateful objects cannot be re-used by simply calling connect()
     * on this connection again. This is unlike the behavior during unexpected disconnects
     * (and subsequent connections). In that case, all state is preserved to allow for
     * more seamless error recovery.
     * 
     * @param unavailablePresence the presence packet to send during shutdown.
     */
    public abstract void disconnect(Presence unavailablePresence);

    /**
     * Adds a new listener that will be notified when new Connections are created. Note
     * that newly created connections will not be actually connected to the server.
     * 
     * @param connectionCreationListener a listener interested on new connections.
     */
    public static void addConnectionCreationListener(
            ConnectionCreationListener connectionCreationListener) {
        connectionEstablishedListeners.add(connectionCreationListener);
    }

    /**
     * Removes a listener that was interested in connection creation events.
     * 
     * @param connectionCreationListener a listener interested on new connections.
     */
    public static void removeConnectionCreationListener(
            ConnectionCreationListener connectionCreationListener) {
        connectionEstablishedListeners.remove(connectionCreationListener);
    }

    /**
     * Get the collection of listeners that are interested in connection creation events.
     * 
     * @return a collection of listeners interested on new connections.
     */
    protected static Collection<ConnectionCreationListener> getConnectionCreationListeners() {
        return Collections.unmodifiableCollection(connectionEstablishedListeners);
    }

    /**
     * Adds a connection listener to this connection that will be notified when
     * the connection closes or fails. The connection needs to already be connected
     * or otherwise an IllegalStateException will be thrown.
     * 
     * @param connectionListener a connection listener.
     */
    public void addConnectionListener(ConnectionListener connectionListener) {
        if (!isConnected()) {
            throw new IllegalStateException("Not connected to server.");
        }
        if (connectionListener == null) {
            return;
        }
        if (!connectionListeners.contains(connectionListener)) {
            connectionListeners.add(connectionListener);
        }
    }

    /**
     * Removes a connection listener from this connection.
     * 
     * @param connectionListener a connection listener.
     */
    public void removeConnectionListener(ConnectionListener connectionListener) {
        connectionListeners.remove(connectionListener);
    }

    /**
     * Get the collection of listeners that are interested in connection events.
     * 
     * @return a collection of listeners interested on connection events.
     */
    protected Collection<ConnectionListener> getConnectionListeners() {
        return connectionListeners;
    }

    /**
     * Creates a new packet collector for this connection. A packet filter determines
     * which packets will be accumulated by the collector. A PacketCollector is
     * more suitable to use than a {@link PacketListener} when you need to wait for
     * a specific result.
     * 
     * @param packetFilter the packet filter to use.
     * @return a new packet collector.
     */
    public PacketCollector createPacketCollector(PacketFilter packetFilter) {
        PacketCollector collector = new PacketCollector(this, packetFilter);
        // Add the collector to the list of active collectors.
        collectors.add(collector);
        return collector;
    }

    /**
     * Remove a packet collector of this connection.
     * 
     * @param collector a packet collectors which was created for this connection.
     */
    protected void removePacketCollector(PacketCollector collector) {
        collectors.remove(collector);
    }

    /**
     * Get the collection of all packet collectors for this connection.
     * 
     * @return a collection of packet collectors for this connection.
     */
    protected Collection<PacketCollector> getPacketCollectors() {
        return collectors;
    }

    /**
     * Registers a packet listener with this connection. A packet filter determines
     * which packets will be delivered to the listener. If the same packet listener
     * is added again with a different filter, only the new filter will be used.
     * 
     * @param packetListener the packet listener to notify of new received packets.
     * @param packetFilter   the packet filter to use.
     */
    public void addPacketListener(PacketListener packetListener, PacketFilter packetFilter) {
        if (packetListener == null) {
            throw new NullPointerException("Packet listener is null.");
        }
        ListenerWrapper wrapper = new ListenerWrapper(packetListener, packetFilter);
        recvListeners.put(packetListener, wrapper);
    }

    /**
     * Removes a packet listener for received packets from this connection.
     * 
     * @param packetListener the packet listener to remove.
     */
    public void removePacketListener(PacketListener packetListener) {
        recvListeners.remove(packetListener);
    }

    /**
     * Get a map of all packet listeners for received packets of this connection.
     * 
     * @return a map of all packet listeners for received packets.
     */
    protected Map<PacketListener, ListenerWrapper> getPacketListeners() {
        return recvListeners;
    }

    /**
     * Registers a packet listener with this connection. The listener will be
     * notified of every packet that this connection sends. A packet filter determines
     * which packets will be delivered to the listener. Note that the thread
     * that writes packets will be used to invoke the listeners. Therefore, each
     * packet listener should complete all operations quickly or use a different
     * thread for processing.
     * 
     * @param packetListener the packet listener to notify of sent packets.
     * @param packetFilter   the packet filter to use.
     */
    public void addPacketSendingListener(PacketListener packetListener, PacketFilter packetFilter) {
        if (packetListener == null) {
            throw new NullPointerException("Packet listener is null.");
        }
        ListenerWrapper wrapper = new ListenerWrapper(packetListener, packetFilter);
        sendListeners.put(packetListener, wrapper);
    }

    /**
     * Removes a packet listener for sending packets from this connection.
     * 
     * @param packetListener the packet listener to remove.
     */
    public void removePacketSendingListener(PacketListener packetListener) {
        sendListeners.remove(packetListener);
    }

    /**
     * Get a map of all packet listeners for sending packets of this connection.
     * 
     * @return a map of all packet listeners for sent packets.
     */
    protected Map<PacketListener, ListenerWrapper> getPacketSendingListeners() {
        return sendListeners;
    }


    /**
     * Process all packet listeners for sending packets.
     * 
     * @param packet the packet to process.
     */
    protected void firePacketSendingListeners(Packet packet) {
        // Notify the listeners of the new sent packet
        for (ListenerWrapper listenerWrapper : sendListeners.values()) {
            listenerWrapper.notifyListener(packet);
        }
    }

    /**
     * Registers a packet interceptor with this connection. The interceptor will be
     * invoked every time a packet is about to be sent by this connection. Interceptors
     * may modify the packet to be sent. A packet filter determines which packets
     * will be delivered to the interceptor.
     *
     * @param packetInterceptor the packet interceptor to notify of packets about to be sent.
     * @param packetFilter      the packet filter to use.
     */
    public void addPacketInterceptor(PacketInterceptor packetInterceptor,
            PacketFilter packetFilter) {
        if (packetInterceptor == null) {
            throw new NullPointerException("Packet interceptor is null.");
        }
        interceptors.put(packetInterceptor, new InterceptorWrapper(packetInterceptor, packetFilter));
    }

    /**
     * Removes a packet interceptor.
     *
     * @param packetInterceptor the packet interceptor to remove.
     */
    public void removePacketInterceptor(PacketInterceptor packetInterceptor) {
        interceptors.remove(packetInterceptor);
    }
    
    public boolean isSendPresence() {
        return config.isSendPresence();
    }

    /**
     * Get a map of all packet interceptors for sending packets of this connection.
     * 
     * @return a map of all packet interceptors for sending packets.
     */
    protected Map<PacketInterceptor, InterceptorWrapper> getPacketInterceptors() {
        return interceptors;
    }

    /**
     * Process interceptors. Interceptors may modify the packet that is about to be sent.
     * Since the thread that requested to send the packet will invoke all interceptors, it
     * is important that interceptors perform their work as soon as possible so that the
     * thread does not remain blocked for a long period.
     * 
     * @param packet the packet that is going to be sent to the server
     */
    protected void firePacketInterceptors(Packet packet) {
        if (packet != null) {
            for (InterceptorWrapper interceptorWrapper : interceptors.values()) {
                interceptorWrapper.notifyListener(packet);
            }
        }
    }

    /**
     * Initialize the {@link #debugger}. You can specify a customized {@link SmackDebugger}
     * by setup the system property <code>smack.debuggerClass</code> to the implementation.
     * 
     * @throws IllegalStateException if the reader or writer isn't yet initialized.
     * @throws IllegalArgumentException if the SmackDebugger can't be loaded.
     */
    protected void initDebugger() {
        if (reader == null || writer == null) {
            throw new NullPointerException("Reader or writer isn't initialized.");
        }
        // If debugging is enabled, we open a window and write out all network traffic.
        if (config.isDebuggerEnabled()) {
            if (debugger == null) {
                // Detect the debugger class to use.
                String className = null;
                // Use try block since we may not have permission to get a system
                // property (for example, when an applet).
                try {
                    className = System.getProperty("smack.debuggerClass");
                }
                catch (Throwable t) {
                    // Ignore.
                }
                Class<?> debuggerClass = null;
                if (className != null) {
                    try {
                        debuggerClass = Class.forName(className);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (debuggerClass == null) {
                    try {
                        debuggerClass =
                                Class.forName("de.measite.smack.AndroidDebugger");
                    }
                    catch (Exception ex) {
                        try {
                            debuggerClass =
                                    Class.forName("org.jivesoftware.smack.debugger.ConsoleDebugger");
                        }
                        catch (Exception ex2) {
                            ex2.printStackTrace();
                        }
                    }
                }
                // Create a new debugger instance. If an exception occurs then disable the debugging
                // option
                try {
                    Constructor<?> constructor = debuggerClass
                            .getConstructor(Connection.class, Writer.class, Reader.class);
                    debugger = (SmackDebugger) constructor.newInstance(this, writer, reader);
                    reader = debugger.getReader();
                    writer = debugger.getWriter();
                }
                catch (Exception e) {
                    throw new IllegalArgumentException("Can't initialize the configured debugger!", e);
                }
            }
            else {
                // Obtain new reader and writer from the existing debugger
                reader = debugger.newConnectionReader(reader);
                writer = debugger.newConnectionWriter(writer);
            }
        }
        
    }

    /**
     * Set the servers Entity Caps node
     * 
     * Connection holds this information in order to avoid a dependency to
     * smackx where EntityCapsManager lives from smack.
     * 
     * @param node
     */
    protected void setServiceCapsNode(String node) {
        serviceCapsNode = node;
    }

    /**
     * Retrieve the servers Entity Caps node
     * 
     * Connection holds this information in order to avoid a dependency to
     * smackx where EntityCapsManager lives from smack.
     * 
     * @return
     */
    public String getServiceCapsNode() {
        return serviceCapsNode;
    }

    /**
     * A wrapper class to associate a packet filter with a listener.
     */
    protected static class ListenerWrapper {

        private PacketListener packetListener;
        private PacketFilter packetFilter;

        /**
         * Create a class which associates a packet filter with a listener.
         * 
         * @param packetListener the packet listener.
         * @param packetFilter the associated filter or null if it listen for all packets.
         */
        public ListenerWrapper(PacketListener packetListener, PacketFilter packetFilter) {
            this.packetListener = packetListener;
            this.packetFilter = packetFilter;
        }

        /**
         * Notify and process the packet listener if the filter matches the packet.
         * 
         * @param packet the packet which was sent or received.
         */
        public void notifyListener(Packet packet) {
            if (packetFilter == null || packetFilter.accept(packet)) {

                packetListener.processPacket(packet);
            }
        }
    }

    /**
     * A wrapper class to associate a packet filter with an interceptor.
     */
    protected static class InterceptorWrapper {

        private PacketInterceptor packetInterceptor;
        private PacketFilter packetFilter;

        /**
         * Create a class which associates a packet filter with an interceptor.
         * 
         * @param packetInterceptor the interceptor.
         * @param packetFilter the associated filter or null if it intercepts all packets.
         */
        public InterceptorWrapper(PacketInterceptor packetInterceptor, PacketFilter packetFilter) {
            this.packetInterceptor = packetInterceptor;
            this.packetFilter = packetFilter;
        }

        public boolean equals(Object object) {
            if (object == null) {
                return false;
            }
            if (object instanceof InterceptorWrapper) {
                return ((InterceptorWrapper) object).packetInterceptor
                        .equals(this.packetInterceptor);
            }
            else if (object instanceof PacketInterceptor) {
                return object.equals(this.packetInterceptor);
            }
            return false;
        }

        /**
         * Notify and process the packet interceptor if the filter matches the packet.
         * 
         * @param packet the packet which will be sent.
         */
        public void notifyListener(Packet packet) {
            if (packetFilter == null || packetFilter.accept(packet)) {
                packetInterceptor.interceptPacket(packet);
            }
        }
    }
}
