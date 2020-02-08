package de.hhu.bsinfo.observatory.jucx;

import de.hhu.bsinfo.observatory.benchmark.Benchmark;
import de.hhu.bsinfo.observatory.benchmark.result.Status;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Stack;
import org.openucx.jucx.ucp.UcpConnectionRequest;
import org.openucx.jucx.ucp.UcpContext;
import org.openucx.jucx.ucp.UcpEndpoint;
import org.openucx.jucx.ucp.UcpEndpointParams;
import org.openucx.jucx.ucp.UcpListener;
import org.openucx.jucx.ucp.UcpListenerParams;
import org.openucx.jucx.ucp.UcpMemory;
import org.openucx.jucx.ucp.UcpParams;
import org.openucx.jucx.ucp.UcpRemoteKey;
import org.openucx.jucx.ucp.UcpRequest;
import org.openucx.jucx.ucp.UcpWorker;
import org.openucx.jucx.ucp.UcpWorkerParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JucxBenchmark extends Benchmark {
    private static final Logger LOGGER = LoggerFactory.getLogger(JucxBenchmark.class);

    UcpContext context;
    UcpWorker worker;
    UcpListener listener;
    UcpEndpoint clientToServer, serverToClient;
    UcpMemory sendMemory, recvMemory;
    UcpConnectionRequest connectionRequest;

    long remoteAddress;
    UcpRemoteKey remoteKey;
    Stack<Closeable> resources = new Stack<>();

    @Override
    protected Status initialize() {
        LOGGER.info("Initializing...");
        UcpParams params = new UcpParams().requestRmaFeature()
                .requestStreamFeature().requestTagFeature().requestWakeupFeature();
        context = new UcpContext(params);
        worker = context.newWorker(new UcpWorkerParams());

        resources.add(context);
        resources.add(worker);
        return Status.OK;
    }

    @Override
    protected Status serve(InetSocketAddress bindAddress) {
       LOGGER.info("Listener started on {}", bindAddress);
       UcpListenerParams listenerParams = new UcpListenerParams().setSockAddr(bindAddress)
                .setConnectionHandler(request -> {
                   this.connectionRequest = request;
                });
        listener = worker.newListener(listenerParams);
        resources.add(listener);

        while (this.connectionRequest == null) {
            if (worker.progress() == 0) {
                worker.waitForEvents();
            }
        }

        UcpEndpointParams endpointParams = new UcpEndpointParams().setConnectionRequest(connectionRequest)
                .setPeerErrorHadnlingMode();
        serverToClient = worker.newEndpoint(endpointParams);
        resources.add(serverToClient);

        // Temporary workaround until new connection establishment protocol in UCX.
        for (int i = 0; i < 10; i++) {
            worker.progress();
            try {
                Thread.sleep(10);
            } catch (Exception ignored) { }
        }

        return Status.OK;
    }

    @Override
    protected Status connect(InetSocketAddress bindAddress, InetSocketAddress serverAddress) {
        LOGGER.info("Connecting to {}", serverAddress);
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        UcpEndpointParams epParams = new UcpEndpointParams().setSocketAddress(serverAddress)
                .setPeerErrorHadnlingMode();
        clientToServer = worker.newEndpoint(epParams);
        resources.add(clientToServer);
        worker.progressRequest( worker.flushNonBlocking(null));
        return Status.OK;
    }

    private void exchangeMemoryInformation() {
        ByteBuffer recvMemoryRkey = recvMemory.getRemoteKeyBuffer();
        ByteBuffer sendMessage = ByteBuffer.allocateDirect(8 + recvMemoryRkey.capacity());
        ByteBuffer recvMessage = ByteBuffer.allocateDirect(4096);

        sendMessage.putLong(recvMemory.getAddress());
        sendMessage.put(recvMemoryRkey);
        sendMessage.clear();

        UcpRequest sendRequest;
        UcpRequest recvRequest;
        if (clientToServer != null) {
            sendRequest = clientToServer.sendStreamNonBlocking(sendMessage, null);
            recvRequest = clientToServer.recvStreamNonBlocking(recvMessage, 0L, null);
        } else {
            sendRequest = serverToClient.sendStreamNonBlocking(sendMessage, null);
            recvRequest = serverToClient.recvStreamNonBlocking(recvMessage, 0L, null);
        }

        worker.progressRequest(sendRequest);
        worker.progressRequest(recvRequest);

        remoteAddress = recvMessage.getLong();
        if (clientToServer != null) {
            remoteKey = clientToServer.unpackRemoteKey(recvMessage);
        } else {
            remoteKey = serverToClient.unpackRemoteKey(recvMessage);
        }
        resources.add(remoteKey);
    }

    @Override
    protected Status prepare(int operationSize) {
        LOGGER.info("Preparing memory regions and exchanging metadata");
        sendMemory = context.registerMemory(ByteBuffer.allocateDirect(operationSize));
        recvMemory = context.registerMemory(ByteBuffer.allocateDirect(operationSize));

        resources.add(sendMemory);
        resources.add(recvMemory);

        exchangeMemoryInformation();
        return Status.OK;
    }

    @Override
    protected Status cleanup() {
        LOGGER.info("Cleanup ...");
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (IOException e) {
                return Status.FILE_ERROR;
            }
        }
        return Status.OK;
    }

    @Override
    protected Status fillReceiveQueue() {
        LOGGER.info("Posing recv request");
        worker.recvTaggedNonBlocking(recvMemory.getAddress(), recvMemory.getLength(), 0, 0, null);
        return Status.OK;
    }

    @Override
    protected Status sendMultipleMessages(int messageCount) {
        LOGGER.info("Sending {} messages", messageCount);
        UcpRequest[] requests = new UcpRequest[messageCount];
        UcpEndpoint endpoint = (clientToServer != null) ? clientToServer : serverToClient;

        for (int i = 0; i < messageCount; i++) {
            requests[i] = endpoint.sendTaggedNonBlocking(sendMemory.getAddress(),
                    sendMemory.getLength(), 0, null);
        }

        while (Arrays.stream(requests).allMatch(UcpRequest::isCompleted)) {
            worker.progress();
        }

        return Status.OK;
    }

    @Override
    protected Status receiveMultipleMessage(int messageCount) {
        LOGGER.info("Receiving {} messages", messageCount);
        UcpRequest[] requests = new UcpRequest[messageCount];

        for (int i = 0; i < messageCount; i++) {
            requests[i] = worker.recvTaggedNonBlocking(recvMemory.getAddress(),
                    recvMemory.getLength(), 0, 0, null);
        }

        while (Arrays.stream(requests).allMatch(UcpRequest::isCompleted)) {
            worker.progress();
        }

        return Status.OK;
    }

    @Override
    protected Status performMultipleRdmaOperations(RdmaMode mode, int operationCount) {
        LOGGER.info("Performing {} RDMA {} operations", operationCount, mode);
        UcpRequest[] requests = new UcpRequest[operationCount];
        UcpEndpoint endpoint = (clientToServer != null) ? clientToServer : serverToClient;

        for (int i = 0; i < operationCount; i++) {
            if (mode == RdmaMode.READ) {
                requests[i] = endpoint.getNonBlocking(remoteAddress, remoteKey,
                        recvMemory.getAddress(), recvMemory.getLength(), null);
            } else {
                requests[i] = endpoint.putNonBlocking(sendMemory.getAddress(), sendMemory.getLength(),
                        remoteAddress, remoteKey, null);
            }
        }

        while (Arrays.stream(requests).allMatch(UcpRequest::isCompleted)) {
            worker.progress();
        }

        return Status.OK;
    }

    @Override
    protected Status sendSingleMessage() {
        sendMultipleMessages(1);
        return Status.OK;
    }

    @Override
    protected Status performSingleRdmaOperation(RdmaMode mode) {
        return performMultipleRdmaOperations(mode, 1);
    }

    @Override
    protected Status performPingPongIterationServer() {
        sendMultipleMessages(1);
        receiveMultipleMessage(1);
        return Status.OK;
    }

    @Override
    protected Status performPingPongIterationClient() {
        receiveMultipleMessage(1);
        sendMultipleMessages(1);
        return Status.OK;
    }
}
