package simple.remoting.transport.netty.codec;

import common.enums.CompressTypeEnum;
import common.enums.SerializationTypeEnum;
import common.extension.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import simple.compress.Compress;
import simple.remoting.constants.RpcConstants;
import simple.remoting.dto.RpcMessage;
import simple.serialize.Serializer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * RpcMessageEncoder 解码器
 *
 * @author 执笔画棠
 * @date 2025/10/16 17:59
 **/
@Slf4j
public class RpcMessageEncoder extends MessageToByteEncoder<RpcMessage> {
    /*
     * <pre>
     *   0     1     2     3     4        5     6     7     8         9          10      11     12  13  14   15 16
     *   +-----+-----+-----+-----+--------+----+----+----+------+-----------+-------+----- --+-----+-----+-------+
     *   |   magic   code        |version | full length         | messageType| codec|compress|    RequestId       |
     *   +-----------------------+--------+---------------------+-----------+-----------+-----------+------------+
     *   |                                                                                                       |
     *   |                                         body                                                          |
     *   |                                                                                                       |
     *   |                                        ... ...                                                        |
     *   +-------------------------------------------------------------------------------------------------------+
     * 4B  magic code（魔法数）   1B version（版本）   4B full length（消息长度）    1B messageType（消息类型）
     * 1B compress（压缩类型） 1B codec（序列化类型）    4B  requestId（请求的Id）
     * body（object类型数据）
     * </pre>
     */

    //生成一个唯一的请求id
    /*
    在一个 RPC (远程过程调用) 框架中，通信通常是异步的。这意味着：
    客户端发送请求 A，然后不等待响应，马上又发送了请求 B 和请求 C。
    服务器处理这些请求的快慢可能不同，可能会先完成 B 的处理，再完成 C，最后完成 A。
    服务器会把响应 B、响应 C、响应 A 发回给客户端。
    通过这个唯一的id确定每一个请求，不能用简单的int，因为netty是多线程的
    如果单纯用int再++，会出现线程安全问题
    线程 1 读取 counter 的值，得到 0。
    此时，操作系统切换到线程 2。
    线程 2 也读取 counter 的值，得到的也是 0。
    线程 2 将 0 加一得到 1，并将 1 写回给 counter。counter 现在是 1。线程 2 将 ID 0 写入请求。
    操作系统切换回线程 1。
    线程 1 之前已经读到了 0，它也将 0 加一得到 1，并把 1 写回给 counter。counter 仍然是 1。线程 1 也将 ID 0 写入请求。
    结果：两个不同的请求被分配了同一个 ID 0！ 这就破坏了唯一性原则
    而用并发原子类是线程安全的
     */
    //初始值为0
    private static final AtomicInteger ATOMIC_INTEGER = new AtomicInteger(0);

    /*
     * 编码RpcMessage消息,将其转换为字节流并写入到ByteBuf中
     *
     * @param ctx 通道处理上下文,用于操作通道
     *
     * @param rpcMessage 要编码的RpcMessage消息
     *
     * @param out 输出字节缓冲区,用于存储编码后的字节流
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage rpcMessage, ByteBuf out) throws Exception {
        try {
            //写入魔数和版本号等信息
            out.writeBytes(RpcConstants.MAGIC_NUMBER);
            out.writeByte(RpcConstants.VERSION);
            //
            out.writerIndex(out.writerIndex() + 4);
            byte messageType = rpcMessage.getMessageType();
            out.writeByte(messageType);
            //写入消息类型和标记序列化方法的codec
            //codec即0x01这些
            out.writeByte(rpcMessage.getCodec());
            //TODO这里应该是有错的，应该是写入name，而不是又写入一遍codec
            //out.writeByte(CompressTypeEnum.GZIP.getCode());
            out.writeByte(rpcMessage.getCompress());
            //写入标记
            out.writeInt(ATOMIC_INTEGER.getAndIncrement());

            byte[] bodyBytes = null;
            //fullLength初始值为head length,因为head length是固定的
            int fullLength = RpcConstants.HEAD_LENGTH;
            if (messageType != RpcConstants.HEARTBEAT_REQUEST_TYPE && messageType != RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
                //非心跳请求响应,则序列化对象,这里根据codec，如0x01找对应的序列化方式
                String codecName = SerializationTypeEnum.getName(rpcMessage.getCodec());
                log.info("codec name is:[{}]", codecName);
                //用扩展加载器获取对应的序列化实例对象
                Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
                        .getExtension(codecName);
                //这里就是得到序列化后的字节数组,调用序列化实例的serialize方法，传入要序列化的数据data
                bodyBytes = serializer.serialize(rpcMessage.getData());

                //然后压缩
                String compressName = CompressTypeEnum.getName(rpcMessage.getCompress());
                Compress compress = ExtensionLoader.getExtensionLoader(Compress.class)
                        .getExtension(compressName);
                bodyBytes = compress.compress(bodyBytes);
                // 写入压缩后的字节长度
                fullLength += bodyBytes.length;
            }

            if (bodyBytes != null) {
                out.writeBytes(bodyBytes);
            }
            // wirteIndex是当前写入索引,fullLength是消息总长度,包括head length和body length
            // 计算出消息总长度后,将其写入到full length字段中
            int writeIndex = out.writerIndex();
            out.writerIndex(writeIndex - fullLength + RpcConstants.MAGIC_NUMBER.length + 1);
            out.writeInt(fullLength);
            out.writerIndex(writeIndex);
        } catch (Exception e) {
            log.error("Encode request error!", e);
        }
    }
}
