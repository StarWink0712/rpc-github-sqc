package simple.remoting.transport.netty.client;

import simple.remoting.dto.RpcResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UnprocessedRequests 未处理请求表
 *
 * @author 执笔画棠
 * @date 2025/10/19 21:28
 **/
/*
 *并发请求 (The "Why"):
 *在一个真实的系统中，不可能只有一个线程在发起 RPC 调用。
 * 可能会有几十上百个业务线程（比如处理用户 HTTP 请求的线程）同时通过 NettyRpcClient 去请求远程服务。
 *这些请求会被几乎同时发送到网络上，它们是“并发的”、“乱序的”。
 *异步响应 (The "Problem"):
 *服务器处理这些请求的快慢也不同，网络延迟也不同。
 * 因此，客户端收到响应的顺序和发送请求的顺序完全没有关系。
 *这就带来了一个巨大的挑战：当一个 Netty I/O 线程收到一个响应时，
 * 它怎么知道这个响应是应该给线程 A，还是线程 B，还是线程 C 的？如果匹配错了，整个系统的数据就全乱了。
 *ID + Map 的解决方案 (The "How"):
 *rpcRequest.getRequestId(): 就像一个独一无二的快递单号。每个请求在发出前都会被分配一个。
 *resultFuture: 就像一个凭证或者一个约定。业务线程说：“我拿着这张凭证，等待单号为 XXX 的包裹”。
 *unprocessedRequests.put(...): 这个操作就是把“快递单号”和“凭证”绑定在一起，
 * 存放在一个公共的、谁都能访问的“前台（UnprocessedRequests）”里。
 */
public class UnprocessedRequests {
    //未处理的请求
    private static final Map<String, CompletableFuture<RpcResponse<Object>>> UNPROCESSED_RESPONSE_FUTURES = new ConcurrentHashMap<>();

    //添加未处理的请求,参数二是为这个请求创建的 CompletableFuture 对象。
    public void put(String resquestId, CompletableFuture<RpcResponse<Object>> future) {
        UNPROCESSED_RESPONSE_FUTURES.put(resquestId, future);
    }

    //完成未处理的请求
    public void complete(RpcResponse<Object> rpcResponse) {
        CompletableFuture<RpcResponse<Object>> future = UNPROCESSED_RESPONSE_FUTURES.remove(rpcResponse.getRequestId());
        if (null != future) {
            future.complete(rpcResponse);
        } else {
            throw new IllegalStateException();
        }
    }
}
