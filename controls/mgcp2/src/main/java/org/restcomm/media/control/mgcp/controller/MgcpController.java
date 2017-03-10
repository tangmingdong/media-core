/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag. 
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.restcomm.media.control.mgcp.controller;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.log4j.Logger;
import org.restcomm.media.control.mgcp.command.MgcpCommand;
import org.restcomm.media.control.mgcp.command.MgcpCommandProvider;
import org.restcomm.media.control.mgcp.endpoint.MgcpEndpointManager;
import org.restcomm.media.control.mgcp.exception.DuplicateMgcpTransactionException;
import org.restcomm.media.control.mgcp.exception.MgcpTransactionNotFoundException;
import org.restcomm.media.control.mgcp.message.MessageDirection;
import org.restcomm.media.control.mgcp.message.MgcpMessage;
import org.restcomm.media.control.mgcp.message.MgcpMessageObserver;
import org.restcomm.media.control.mgcp.message.MgcpRequest;
import org.restcomm.media.control.mgcp.message.MgcpResponse;
import org.restcomm.media.control.mgcp.message.MgcpResponseCode;
import org.restcomm.media.control.mgcp.network.MgcpNettyChannel;
import org.restcomm.media.control.mgcp.transaction.MgcpTransactionManager;
import org.restcomm.media.spi.ControlProtocol;
import org.restcomm.media.spi.Endpoint;
import org.restcomm.media.spi.EndpointInstaller;
import org.restcomm.media.spi.ServerManager;

/**
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 *
 */
public class MgcpController implements ServerManager, MgcpMessageObserver {

    private static final Logger log = Logger.getLogger(MgcpController.class);

    // MGCP Components
    private final MgcpNettyChannel channel;
    private final MgcpTransactionManager transactions;
    private final MgcpEndpointManager endpoints;
    private final MgcpCommandProvider commands;

    // MGCP Controller State
    private final String address;
    private final int port;
    private boolean active;

    public MgcpController(String address, int port, MgcpNettyChannel channel, MgcpTransactionManager transactions, MgcpEndpointManager endpoints, MgcpCommandProvider commands) {
        // MGCP Components
        this.channel = channel;
        this.transactions = transactions;
        this.endpoints = endpoints;
        this.commands = commands;

        // MGCP Controller State
        this.address = address;
        this.port = port;
        this.active = false;
    }

    @Override
    public ControlProtocol getControlProtocol() {
        return ControlProtocol.MGPC;
    }

    @Override
    public void activate() throws IllegalStateException {
        if (this.active) {
            throw new IllegalStateException("Controller is already active");
        } else {
            // Open MGCP channel and bind it to configured address
            this.channel.open();
            this.channel.bind(new InetSocketAddress(this.address, this.port));

            if (log.isInfoEnabled()) {
                log.info("Opened MGCP channel at " + this.address + ":" + this.port);
            }

            // Register as observer to other components to receive notifications
            this.channel.observe(this);
            this.transactions.observe(this);
            this.endpoints.observe(this);
            this.active = true;

            if (log.isInfoEnabled()) {
                log.info("MGCP controller is active");
            }
        }
    }

    @Override
    public void deactivate() throws IllegalStateException {
        if (this.active) {
            // TODO stop resources
            this.channel.close();
            this.channel.forget(this);
            
            if (log.isInfoEnabled()) {
                log.info("MGCP channel is closed");
            }
            
            this.transactions.forget(this);
            this.endpoints.forget(this);
            // TODO clear transactions
            this.active = false;
            
            if (log.isInfoEnabled()) {
                log.info("MGCP controller is inactive");
            }
        } else {
            throw new IllegalStateException("Controller is already inactive");
        }
    }

    @Override
    public boolean isActive() {
        return this.active;
    }

    @Override
    public void onStarted(Endpoint endpoint, EndpointInstaller installer) {
        // Legacy stuff
    }

    @Override
    public void onStopped(Endpoint endpoint) {
        // Legacy stuff
    }

    @Override
    public void onMessage(InetSocketAddress from, InetSocketAddress to, MgcpMessage message, MessageDirection direction) {
        // FIXME Tech debt
        message.setSender(from);
        message.setRecipient(to);
        
        switch (direction) {
            case INCOMING:
                if (message.isRequest()) {
                    onIncomingRequest((MgcpRequest) message);
                } else {
                    onIncomingResponse((MgcpResponse) message);
                }
                break;

            case OUTGOING:
                if (message.isRequest()) {
                    onOutgoingRequest((MgcpRequest) message);
                } else {
                    onOutgoingResponse((MgcpResponse) message);
                }
                break;

            default:
                throw new IllegalArgumentException("Unknown message direction: " + direction.name());
        }
    }

    private void onIncomingRequest(MgcpRequest request) {
        // Get command to be executed
        MgcpCommand command = this.commands.provide(request.getRequestType(), request.getTransactionId(), request.getParameters());

        try {
            // Start transaction that will execute the command
            this.transactions.process(request.getSender(), request.getRecipient(), request, command, MessageDirection.INCOMING);
        } catch (DuplicateMgcpTransactionException e) {
            // Transaction is already being processed
            // Send provisional message
            MgcpResponseCode provisional = MgcpResponseCode.TRANSACTION_BEING_EXECUTED;

            if (log.isDebugEnabled()) {
                log.debug("Received duplicate request tx=" + request.getTransactionId() + " from " + request.getSender().toString()
                        + ". Sending provisional response with code " + provisional.code());
            }

            try {
                sendResponse(request.getRecipient(), request.getSender(), request.getTransactionId(), provisional.code(), provisional.message());
            } catch (IOException e1) {
                log.error("Could not send provisional response to call agent, regarding transaction " + request.getTransactionId(), e);
            }
        }
    }

    private void onOutgoingRequest(MgcpRequest request) {
        try {
            // Start transaction
            this.transactions.process(request.getSender(), request.getRecipient(), request, null, MessageDirection.OUTGOING);
            // Send request to call agent
            this.channel.send(request);
        } catch (DuplicateMgcpTransactionException e) {
            log.error(e.getMessage() + ". Request wont' be sent to call agent.");
        } catch (IOException e) {
            log.error("Could not send MGCP request to call agent: " + request.toString(), e);
        }
    }

    private void onIncomingResponse(MgcpResponse response) {
        try {
            // Close transaction
            this.transactions.process(response.getSender(), response.getRecipient(), response, MessageDirection.INCOMING);
        } catch (MgcpTransactionNotFoundException e) {
            log.error(e.getMessage());
        }
    }

    private void onOutgoingResponse(MgcpResponse response) {
        try {
            // Close transaction
            this.transactions.process(response.getSender(), response.getRecipient(), response, MessageDirection.OUTGOING);
            // Send response to call agent
            this.channel.send(response);
        } catch (MgcpTransactionNotFoundException e) {
            log.error(e.getMessage() + ". Response won't be sent to call agent.");
        } catch (IOException e) {
            log.error("Could not send MGCP response to call agent: " + response.toString(), e);
        }
    }

    private void sendResponse(InetSocketAddress from, InetSocketAddress to, int transactionId, int code, String message) throws IOException {
        MgcpResponse response = new MgcpResponse();
        response.setSender(from);
        response.setRecipient(to);
        response.setTransactionId(transactionId);
        response.setCode(code);
        response.setMessage(message);
        this.channel.send(response);
    }

}