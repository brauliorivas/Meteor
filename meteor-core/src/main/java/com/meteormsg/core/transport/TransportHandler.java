package com.meteormsg.core.transport;

import com.meteormsg.base.RpcSerializer;
import com.meteormsg.base.RpcTransport;
import com.meteormsg.base.enums.Direction;
import com.meteormsg.core.executor.ImplementationWrapper;
import com.meteormsg.core.trackers.IncomingInvocationTracker;
import com.meteormsg.core.transport.packets.InvocationDescriptor;
import com.meteormsg.core.transport.packets.InvocationResponse;
import com.meteormsg.core.trackers.OutgoingInvocationTracker;

import java.io.IOException;
import java.util.Collection;

public class TransportHandler {

    private final RpcSerializer serializer;
    private final RpcTransport transport;
    private final IncomingInvocationTracker incomingInvocationTracker;
    private final OutgoingInvocationTracker outgoingInvocationTracker;

    public TransportHandler(RpcSerializer serializer, RpcTransport transport, IncomingInvocationTracker incomingInvocationTracker, OutgoingInvocationTracker outgoingInvocationTracker) {
        this.serializer = serializer;
        this.transport = transport;
        this.incomingInvocationTracker = incomingInvocationTracker;
        this.outgoingInvocationTracker = outgoingInvocationTracker;

        transport.subscribe(Direction.METHOD_PROXY, this::handleInvocationResponse);
        transport.subscribe(Direction.IMPLEMENTATION, this::handleInvocationRequest);
    }

    private boolean handleInvocationResponse(byte[] bytes) throws ClassNotFoundException {
        InvocationResponse invocationResponse = InvocationResponse.fromBytes(serializer, bytes);
        outgoingInvocationTracker.completeInvocation(invocationResponse);
        return true;
    }

    private boolean handleInvocationRequest(byte[] bytes) throws ClassNotFoundException {
        // deserialize the packet
        InvocationDescriptor invocationDescriptor = InvocationDescriptor.fromBuffer(serializer, bytes);

        // get the invocation handler for this packet
        Collection<ImplementationWrapper> implementations = incomingInvocationTracker.getImplementations().get(invocationDescriptor.getDeclaringClass());

        // if there is no invocation handler, return
        if (implementations == null || implementations.isEmpty()) {
            return false;
        }

        // if there is an invocation handler, call it
        for (ImplementationWrapper implementation : implementations) {
            if (invocationDescriptor.getNamespace() != null && !invocationDescriptor.getNamespace().equals(implementation.getNamespace())) {
                continue;
            }
            try {
                // move to separate threading
                Object response = implementation.invokeOn(invocationDescriptor, invocationDescriptor.getReturnType());
                InvocationResponse invocationResponse = new InvocationResponse(invocationDescriptor.getUniqueInvocationId(), response);
                transport.send(Direction.METHOD_PROXY, invocationResponse.toBytes(serializer));
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
                // if this happens for all handlers, the origin will eventually get a timeout and just fuck off
                return false;
            }
        }

        return true;
    }

    public void stop() throws IOException {
        transport.close();
    }
}
