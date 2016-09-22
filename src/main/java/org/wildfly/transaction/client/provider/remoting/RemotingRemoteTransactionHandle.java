/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package org.wildfly.transaction.client.provider.remoting;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;

import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.MessageOutputStream;
import org.jboss.remoting3.util.BlockingInvocation;
import org.jboss.remoting3.util.InvocationTracker;
import org.jboss.remoting3.util.StreamUtils;
import org.wildfly.transaction.client._private.Log;
import org.wildfly.transaction.client.spi.SimpleTransactionControl;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
class RemotingRemoteTransactionHandle implements SimpleTransactionControl, RemotingRemoteTransaction {

    private final TransactionClientChannel channel;
    private final AtomicInteger statusRef = new AtomicInteger(Status.STATUS_NO_TRANSACTION);
    private final int id;

    RemotingRemoteTransactionHandle(final int id, final TransactionClientChannel channel) {
        this.id = id;
        this.channel = channel;
    }

    public int getId() {
        return id;
    }

    public URI getLocation() {
        return channel.getLocation();
    }

    void begin(int remainingTimeout) throws SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal != Status.STATUS_NO_TRANSACTION) {
            throw Log.log.invalidTxnState();
        }
        synchronized (statusRef) {
            oldVal = statusRef.get();
            if (oldVal != Status.STATUS_NO_TRANSACTION) {
                // unlikely
                throw Log.log.invalidTxnState();
            }
            try {
                final InvocationTracker invocationTracker = channel.getInvocationTracker();
                final BlockingInvocation invocation = invocationTracker.addInvocation(BlockingInvocation::new);
                // write request
                try (MessageOutputStream os = invocationTracker.allocateMessage(invocation)) {
                    os.writeShort(invocation.getIndex());
                    os.writeByte(Protocol.M_UT_BEGIN);
                    Protocol.writeParam(Protocol.P_TXN_CONTEXT, os, id, Protocol.UNSIGNED);
                    final int peerIdentityId = channel.getConnection().getPeerIdentityId();
                    if (peerIdentityId != 0) Protocol.writeParam(Protocol.P_SEC_CONTEXT, os, peerIdentityId, Protocol.UNSIGNED);
                    if (remainingTimeout != 0) Protocol.writeParam(Protocol.P_TXN_TIMEOUT, os, remainingTimeout, Protocol.UNSIGNED);
                } catch (IOException e) {
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.failedToSend(e);
                }
                try (BlockingInvocation.Response response = invocation.getResponse()) {
                    try (MessageInputStream is = response.getInputStream()) {
                        if (is.readUnsignedByte() != Protocol.M_RESP_UT_BEGIN) {
                            throw Log.log.unknownResponse();
                        }
                        int id = is.read();
                        if (id != -1) do {
                            // skip content
                            Protocol.readIntParam(is, StreamUtils.readPackedUnsignedInt32(is));
                        } while (is.read() != -1);
                        if (id == -1) {
                            statusRef.set(Status.STATUS_ACTIVE);
                        } else if (id == Protocol.P_UT_IS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerIllegalStateException();
                        } else if (id == Protocol.P_UT_SYS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerSystemException();
                        } else if (id == Protocol.P_SEC_EXC) {
                            throw Log.log.peerSecurityException();
                        } else {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.unknownResponse();
                        }
                    } catch (IOException e) {
                        statusRef.set(Status.STATUS_UNKNOWN);
                        throw Log.log.responseFailed(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.operationInterrupted();
                } catch (IOException e) {
                    // failed to close the response, but we don't care too much
                    Log.log.outboundException(e);
                }
            } finally {
                statusRef.compareAndSet(Status.STATUS_NO_TRANSACTION, Status.STATUS_UNKNOWN);
            }
        }
    }

    public void disconnect() {
        final AtomicInteger statusRef = this.statusRef;
        synchronized (statusRef) {
            final int oldVal = statusRef.get();
            if (oldVal == Status.STATUS_ACTIVE || oldVal == Status.STATUS_MARKED_ROLLBACK) {
                statusRef.set(Status.STATUS_ROLLEDBACK);
            }
        }
    }

    public void commit() throws RollbackException, HeuristicMixedException, HeuristicRollbackException, SecurityException, SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
            throw Log.log.invalidTxnState();
        }
        synchronized (statusRef) {
            oldVal = statusRef.get();
            if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
                rollback();
                throw Log.log.rollbackOnlyRollback();
            }
            if (oldVal != Status.STATUS_ACTIVE) {
                throw Log.log.invalidTxnState();
            }
            statusRef.set(Status.STATUS_COMMITTING);
            try {
                final InvocationTracker invocationTracker = channel.getInvocationTracker();
                final BlockingInvocation invocation = invocationTracker.addInvocation(BlockingInvocation::new);
                // write request
                try (MessageOutputStream os = invocationTracker.allocateMessage(invocation)) {
                    os.writeShort(invocation.getIndex());
                    os.writeByte(Protocol.M_UT_COMMIT);
                    Protocol.writeParam(Protocol.P_TXN_CONTEXT, os, id, Protocol.UNSIGNED);
                    final int peerIdentityId = channel.getConnection().getPeerIdentityId();
                    if (peerIdentityId != 0) Protocol.writeParam(Protocol.P_SEC_CONTEXT, os, peerIdentityId, Protocol.UNSIGNED);
                } catch (IOException e) {
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.failedToSend(e);
                }
                try (BlockingInvocation.Response response = invocation.getResponse()) {
                    try (MessageInputStream is = response.getInputStream()) {
                        if (is.readUnsignedByte() != Protocol.M_RESP_UT_COMMIT) {
                            throw Log.log.unknownResponse();
                        }
                        int id = is.read();
                        if (id != -1) do {
                            // skip content
                            Protocol.readIntParam(is, StreamUtils.readPackedUnsignedInt32(is));
                        } while (is.read() != -1);
                        if (id == -1) {
                            statusRef.set(Status.STATUS_COMMITTED);
                        } else if (id == Protocol.P_UT_HME_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerHeuristicMixedException();
                        } else if (id == Protocol.P_UT_HRE_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerHeuristicRollbackException();
                        } else if (id == Protocol.P_UT_IS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerIllegalStateException();
                        } else if (id == Protocol.P_UT_RB_EXC) {
                            statusRef.set(Status.STATUS_ROLLEDBACK);
                            throw Log.log.transactionRolledBackByPeer();
                        } else if (id == Protocol.P_UT_SYS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerSystemException();
                        } else if (id == Protocol.P_SEC_EXC) {
                            statusRef.set(oldVal);
                            throw Log.log.peerSecurityException();
                        } else {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.unknownResponse();
                        }
                    } catch (IOException e) {
                        statusRef.set(Status.STATUS_UNKNOWN);
                        throw Log.log.responseFailed(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.operationInterrupted();
                } catch (IOException e) {
                    // failed to close the response, but we don't care too much
                    Log.log.inboundException(e);
                }
            } finally {
                statusRef.compareAndSet(Status.STATUS_COMMITTING, Status.STATUS_UNKNOWN);
            }
        }
    }

    public void rollback() throws SecurityException, SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
            throw Log.log.invalidTxnState();
        }
        synchronized (statusRef) {
            oldVal = statusRef.get();
            if (oldVal != Status.STATUS_ACTIVE && oldVal != Status.STATUS_MARKED_ROLLBACK) {
                throw Log.log.invalidTxnState();
            }
            statusRef.set(Status.STATUS_ROLLING_BACK);
            try {
                final InvocationTracker invocationTracker = channel.getInvocationTracker();
                final BlockingInvocation invocation = invocationTracker.addInvocation(BlockingInvocation::new);
                // write request
                try (MessageOutputStream os = invocationTracker.allocateMessage(invocation)) {
                    os.writeShort(invocation.getIndex());
                    os.writeByte(Protocol.M_UT_ROLLBACK);
                    Protocol.writeParam(Protocol.P_TXN_CONTEXT, os, id, Protocol.UNSIGNED);
                    final int peerIdentityId = channel.getConnection().getPeerIdentityId();
                    if (peerIdentityId != 0) Protocol.writeParam(Protocol.P_SEC_CONTEXT, os, peerIdentityId, Protocol.UNSIGNED);
                } catch (IOException e) {
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.failedToSend(e);
                }
                try (BlockingInvocation.Response response = invocation.getResponse()) {
                    try (MessageInputStream is = response.getInputStream()) {
                        if (is.readUnsignedByte() != Protocol.M_RESP_UT_ROLLBACK) {
                            throw Log.log.unknownResponse();
                        }
                        int id = is.read();
                        if (id != -1) do {
                            // skip content
                            Protocol.readIntParam(is, StreamUtils.readPackedUnsignedInt32(is));
                        } while (is.read() != -1);
                        if (id == -1) {
                            statusRef.set(Status.STATUS_ROLLEDBACK);
                        } else if (id == Protocol.P_UT_IS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerIllegalStateException();
                        } else if (id == Protocol.P_UT_SYS_EXC) {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.peerSystemException();
                        } else if (id == Protocol.P_SEC_EXC) {
                            statusRef.set(oldVal);
                            throw Log.log.peerSecurityException();
                        } else {
                            statusRef.set(Status.STATUS_UNKNOWN);
                            throw Log.log.unknownResponse();
                        }
                    } catch (IOException e) {
                        statusRef.set(Status.STATUS_UNKNOWN);
                        throw Log.log.responseFailed(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    statusRef.set(Status.STATUS_UNKNOWN);
                    throw Log.log.operationInterrupted();
                } catch (IOException e) {
                    // failed to close the response, but we don't care too much
                    Log.log.inboundException(e);
                }
            } finally {
                statusRef.compareAndSet(Status.STATUS_ROLLING_BACK, Status.STATUS_UNKNOWN);
            }
        }
    }

    public void setRollbackOnly() throws SystemException {
        final AtomicInteger statusRef = this.statusRef;
        int oldVal = statusRef.get();
        if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
            return;
        } else if (oldVal != Status.STATUS_ACTIVE) {
            throw Log.log.invalidTxnState();
        }
        synchronized (statusRef) {
            // re-check under lock
            oldVal = statusRef.get();
            if (oldVal == Status.STATUS_MARKED_ROLLBACK) {
                return;
            } else if (oldVal != Status.STATUS_ACTIVE) {
                throw Log.log.invalidTxnState();
            }
            statusRef.set(Status.STATUS_MARKED_ROLLBACK);
        }
    }
}