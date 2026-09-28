package simple.remoting.transport.netty.codec;

import common.enums.CompressTypeEnum;
import common.enums.SerializationTypeEnum;
import common.extension.ExtensionLoader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;
import simple.compress.Compress;
import simple.remoting.constants.RpcConstants;
import simple.remoting.dto.RpcMessage;
import simple.remoting.dto.RpcRequest;
import simple.remoting.dto.RpcResponse;
import simple.serialize.Serializer;

import java.util.Arrays;

/**
 * RpcMessageDecoder 编码器
 *
 * @author 执笔画棠
 * @date 2025/10/16 17:57
 **/
@Slf4j
public class RpcMessageDecoder extends LengthFieldBasedFrameDecoder {

    public RpcMessageDecoder() {
        // lengthFieldOffset: 魔法数占 4 字节，版本号占 1 字节，之后是消息总长度字段。
        // 因此该值为 5
        // lengthFieldLength: 消息总长度占 4 字节。因此该值为 4
        // lengthAdjustment: 消息总长度包含所有数据，此前已读取 9 字节，
        // 剩余长度为 (总长度 - 9)。因此该值为 -9
        // initialBytesToStrip: 我们将手动检查魔法数和版本号，因此不跳过任何字节。
        // 所以该值为 0
        this(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
    }

    /**
     * 自定义协议解码器.
     *
     * @param maxFrameLength      最大帧长度。它决定了可以接收的最大数据长度。
     *                            如果超过，数据将被丢弃。
     * @param lengthFieldOffset   长度字段偏移量。长度字段是跳过指定长度字节的字段。
     * @param lengthFieldLength   长度字段长度。长度字段占用的字节数。
     * @param lengthAdjustment    长度调整值。添加到长度字段值的补偿值。
     * @param initialBytesToStrip 初始要跳过的字节数。
     *                            如果需要接收所有的头+体数据，该值为 0
     *                            如果只需要接收体数据，那么需要跳过头数据占用的字节数。
     */
    public RpcMessageDecoder(int maxFrameLength, int lengthFieldOffset, int lengthFieldLength,
                             int lengthAdjustment, int initialBytesToStrip) {
        super(maxFrameLength, lengthFieldOffset, lengthFieldLength, lengthAdjustment, initialBytesToStrip);
    }


    /*
     * 解码 Netty 接收到的字节流.
     *
     * @param ctx 通道处理上下文，用于操作通道和事件
     *
     * @param in 输入字节流，包含待解码的数据
     *
     * @return 解码后的对象，可能是 RpcMessage 或其他类型
     *
     * @throws Exception 如果解码过程中出现错误
     */
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        //这里是调用父类LengthFieldBasedFrameDecoder的方法解码
        //ctx就是通道嘛，in是从通道中获取到的消息帧，即输入字节流
        Object decoded = super.decode(ctx, in);
        //如果解码后的对象是bytebuf类型，则表明是完整的消息帧
        if (decoded instanceof ByteBuf) {
            //强转成bytebuf类型
            ByteBuf frame = (ByteBuf) decoded;

            //确保可读的长度大于协议头的总长度
            if (frame.readableBytes() >= RpcConstants.TOTAL_LENGTH) {
                try {
                    //调用下面定义的解码方法，将字节流转换为rpcmessage对象
                    return decodeFrame(frame);
                } catch (Exception e) {
                    log.error("Decode frame error!", e);
                    throw e;
                } finally {
                    //释放bytebuf防止内存泄露
                    frame.release();
                }
            }
        }
        //如果解码失败或数据不完整，则返回原始的decoded对象
        return decoded;
    }

    private Object decodeFrame(ByteBuf in) throws Exception {

        //检查魔术，用于识别协议
        checkMagicNumber(in);
        //检查版本号
        checkVersion(in);
        //读取消息总长度
        int fullLength = in.markReaderIndex().readInt();
        //读取消息类型
        byte messageType = in.readByte();
        //读取序列化类型
        byte codecType = in.readByte();
        //读取压缩类型
        byte compressType = in.readByte();
        //读取请求id
        int requestId = in.readInt();
        //根据读取到的信息构建rpc对象，并设置基本信息
        RpcMessage rpcMessage = RpcMessage.builder()
                .codec(codecType)
                .requestId(requestId)
                .messageType(messageType).build();
        //如果是心跳响应消息，则把数据体设置为对应的ping和pong
        if (messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
            rpcMessage.setData(RpcConstants.PING);
        }
        if (messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
            rpcMessage.setData(RpcConstants.PONG);
        }

        //计算消息体的长度
        int bodyLength = fullLength - RpcConstants.HEAD_LENGTH;
        //如果消息体长度大于0，则处理消息体
        if (bodyLength > 0) {
            //创建一个字节数组，用于存放消息体数据
            byte[] bs = new byte[bodyLength];
            //从bytebuf中读取数据到bs中
            in.readBytes(bs);
            //根据压缩类型获取压缩器的名称
            String compressName = CompressTypeEnum.getName(codecType);
            //使用扩展加载器获取压缩器的实例
            Compress compress = ExtensionLoader.getExtensionLoader(Compress.class).getExtension(compressName);
            //解压缩消息体数据
            bs = compress.decompress(bs);

            //处理序列化,根据消息体中的codec值获取序列化类型的name
            String codecName = SerializationTypeEnum.getName(rpcMessage.getCodec());
            log.info("codec name:[{}]", codecName);

            Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class).getExtension(codecName);
            //如果是请求消息，
            if (messageType == RpcConstants.REQUEST_TYPE) {
                //将字节数组反序列化为rpcrequest对象
                RpcRequest tmpValue = serializer.deserialize(bs, RpcRequest.class);
                rpcMessage.setData(tmpValue);
            }
            if (messageType == RpcConstants.RESPONSE_TYPE) {
                //将字节数组反序列化为响应对象
                RpcResponse tmpValue = serializer.deserialize(bs, RpcResponse.class);
                // 将反序列化后的对象设置到RpcMessage的数据部分
                rpcMessage.setData(tmpValue);
            }
        }
        //返回解码好的rpcmessage对象
        return rpcMessage;
    }

    //检查版本号方法
    private void checkVersion(ByteBuf in) {
        byte version = in.readByte();
        //检查版本号是否兼容
        if (version != RpcConstants.VERSION) {
            throw new RuntimeException("version isn't compatiable" + version);
        }
    }

    private void checkMagicNumber(ByteBuf in) {
        // read the first 4 bit, which is the magic number, and compare
        // 获取魔法数的长度
        int len = RpcConstants.MAGIC_NUMBER.length;
        // 创建一个临时字节数组用于存放读取的魔法数
        byte[] tmp = new byte[len];
        // 从ByteBuf中读取魔法数到临时字节数组
        in.readBytes(tmp);
        // 逐字节比较读取到的魔法数与常量中定义的魔法数是否一致
        for (int i = 0; i < len; i++) {
            if (tmp[i] != RpcConstants.MAGIC_NUMBER[i]) {
                // 如果不一致，则抛出非法参数异常，表示协议不匹配
                throw new IllegalArgumentException("Unknown magic code: " + Arrays.toString(tmp));
            }
        }
    }
}
