// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.client.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClient.ConnectFuture;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.io.AbstractWebSocketConnection;

public class WebSocketClientSelectorManager extends SelectorManager
{
    private static final Logger LOG = Log.getLogger(WebSocketClientSelectorManager.class);
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private SslContextFactory sslContextFactory;

    public WebSocketClientSelectorManager(ByteBufferPool bufferPool, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy)
    {
        super();
        this.bufferPool = bufferPool;
        this.executor = executor;
        this.scheduler = scheduler;
        this.policy = policy;
    }

    @Override
    protected void execute(Runnable task)
    {
        this.executor.execute(task);
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    public Connection newConnection(final SocketChannel channel, EndPoint endPoint, final Object attachment) throws IOException
    {
        LOG.debug("newConnection({},{},{})",channel,endPoint,attachment);
        WebSocketClient.ConnectFuture confut = (WebSocketClient.ConnectFuture)attachment;

        try
        {
            String scheme = confut.getWebSocketUri().getScheme();

            if ("wss".equalsIgnoreCase(scheme))
            {
                // Encrypted "wss://"
                if (sslContextFactory != null)
                {
                    SSLEngine engine = newSSLEngine(sslContextFactory,channel);
                    SslConnection sslConnection = new SslConnection(bufferPool,executor,endPoint,engine);
                    EndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();

                    Connection connection = newWebSocketConnection(channel,sslEndPoint,confut);
                    sslEndPoint.setConnection(connection);
                    connectionOpened(connection);
                    return sslConnection;
                }
                else
                {
                    // FIXME: throw error
                    throw new IOException("Cannot init SSL");
                }
            }
            else
            {
                // Standard "ws://"
                return newWebSocketConnection(channel,endPoint,confut);
            }
        }
        catch (IOException e)
        {
            LOG.debug(e);
            confut.failed(null,e);
            // rethrow
            throw e;
        }
    }

    @Override
    protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
    {
        LOG.debug("newEndPoint({}, {}, {})",channel,selectSet,selectionKey);
        return new SelectChannelEndPoint(channel,selectSet,selectionKey,scheduler,policy.getIdleTimeout());
    }

    public SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSSLEngine(peerHost,peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public AbstractWebSocketConnection newWebSocketConnection(SocketChannel channel, EndPoint endPoint, ConnectFuture confut)
    {
        WebSocketClientFactory factory = confut.getFactory();
        WebSocketEventDriver websocket = confut.getWebSocket();

        Executor executor = factory.getExecutor();
        WebSocketPolicy policy = factory.getPolicy();
        ByteBufferPool bufferPool = factory.getBufferPool();
        ScheduledExecutorService scheduler = factory.getScheduler();

        AbstractWebSocketConnection connection = new WebSocketClientConnection(endPoint,executor,scheduler,policy,bufferPool,factory,confut);
        connection.getParser().setIncomingFramesHandler(websocket);

        // TODO: track open websockets? bind open websocket to connection?
        return connection;
    }

    public void setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }
}
