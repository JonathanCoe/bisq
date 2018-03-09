package io.bisq.core.dao.node.full.network;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.bisq.common.Timer;
import io.bisq.common.UserThread;
import io.bisq.common.app.Log;
import io.bisq.core.dao.blockchain.BsqBlockChainReadModel;
import io.bisq.core.dao.blockchain.vo.BsqBlock;
import io.bisq.core.dao.node.messages.GetBsqBlocksRequest;
import io.bisq.core.dao.node.messages.GetBsqBlocksResponse;
import io.bisq.network.p2p.network.CloseConnectionReason;
import io.bisq.network.p2p.network.Connection;
import io.bisq.network.p2p.network.NetworkNode;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Accepts a GetBsqBlocksRequest from a lite nodes and send back a corresponding GetBsqBlocksResponse.
 */
@Slf4j
class GetBsqBlocksRequestHandler {
    private static final long TIMEOUT = 120;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public interface Listener {
        void onComplete();

        void onFault(String errorMessage, Connection connection);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final NetworkNode networkNode;
    private final BsqBlockChainReadModel bsqBlockChainReadModel;
    private final Listener listener;
    private Timer timeoutTimer;
    private boolean stopped;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public GetBsqBlocksRequestHandler(NetworkNode networkNode, BsqBlockChainReadModel bsqBlockChainReadModel, Listener listener) {
        this.networkNode = networkNode;
        this.bsqBlockChainReadModel = bsqBlockChainReadModel;
        this.listener = listener;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onGetBsqBlocksRequest(GetBsqBlocksRequest getBsqBlocksRequest, final Connection connection) {
        Log.traceCall(getBsqBlocksRequest + "\n\tconnection=" + connection);
        List<BsqBlock> bsqBlocks = bsqBlockChainReadModel.getResetBlocksFrom(getBsqBlocksRequest.getFromBlockHeight());
        final GetBsqBlocksResponse bsqBlocksResponse = new GetBsqBlocksResponse(bsqBlocks, getBsqBlocksRequest.getNonce());
        log.debug("bsqBlocksResponse " + bsqBlocksResponse.getRequestNonce());

        if (timeoutTimer == null) {
            timeoutTimer = UserThread.runAfter(() -> {  // setup before sending to avoid race conditions
                        String errorMessage = "A timeout occurred for bsqBlocksResponse:" + bsqBlocksResponse +
                                " on connection:" + connection;
                        handleFault(errorMessage, CloseConnectionReason.SEND_MSG_TIMEOUT, connection);
                    },
                    TIMEOUT, TimeUnit.SECONDS);
        }

        SettableFuture<Connection> future = networkNode.sendMessage(connection, bsqBlocksResponse);
        Futures.addCallback(future, new FutureCallback<Connection>() {
            @Override
            public void onSuccess(Connection connection) {
                if (!stopped) {
                    log.trace("Send DataResponse to {} succeeded. bsqBlocksResponse={}",
                            connection.getPeersNodeAddressOptional(), bsqBlocksResponse);
                    cleanup();
                    listener.onComplete();
                } else {
                    log.trace("We have stopped already. We ignore that networkNode.sendMessage.onSuccess call.");
                }
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                if (!stopped) {
                    String errorMessage = "Sending bsqBlocksResponse to " + connection +
                            " failed. That is expected if the peer is offline. bsqBlocksResponse=" + bsqBlocksResponse + "." +
                            "Exception: " + throwable.getMessage();
                    handleFault(errorMessage, CloseConnectionReason.SEND_MSG_FAILURE, connection);
                } else {
                    log.trace("We have stopped already. We ignore that networkNode.sendMessage.onFailure call.");
                }
            }
        });
    }

    public void stop() {
        cleanup();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleFault(String errorMessage, CloseConnectionReason closeConnectionReason, Connection connection) {
        if (!stopped) {
            log.debug(errorMessage + "\n\tcloseConnectionReason=" + closeConnectionReason);
            cleanup();
            listener.onFault(errorMessage, connection);
        } else {
            log.warn("We have already stopped (handleFault)");
        }
    }

    private void cleanup() {
        stopped = true;
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }
}
