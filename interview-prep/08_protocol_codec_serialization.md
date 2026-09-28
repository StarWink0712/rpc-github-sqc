# 08 Protocol Codec Serialization

## 一句话定位

这个项目自定义了一套 RPC 通信协议，并通过 Netty 的编解码器把 Java 对象转换成网络字节流。请求和响应在网络上传输时，会先经过序列化，再经过 Gzip 压缩，接收方再按相反顺序解压和反序列化。

面试时可以先这样概括：

> 我在项目中设计了一个固定协议头加可变 body 的 RPC 协议。协议头包含 magic number、版本号、消息总长度、消息类型、序列化方式、压缩方式和请求编号，body 存放序列化压缩后的 `RpcRequest` 或 `RpcResponse`。Netty 发送前通过 `RpcMessageEncoder` 编码，接收时通过 `RpcMessageDecoder` 解码，并使用 `LengthFieldBasedFrameDecoder` 根据 full length 字段解决 TCP 粘包半包问题。

## 核心类

- `simple.remoting.dto.RpcMessage`
- `simple.remoting.constants.RpcConstants`
- `simple.remoting.transport.netty.codec.RpcMessageEncoder`
- `simple.remoting.transport.netty.codec.RpcMessageDecoder`
- `simple.serialize.Serializer`
- `simple.serialize.hessian.HessianSerializer`
- `simple.serialize.kyro.KryoSerializer`
- `simple.serialize.protostuff.ProtostuffSerializer`
- `simple.compress.Compress`
- `simple.compress.gzip.GzipCompress`
- `common.enums.SerializationTypeEnum`
- `common.enums.CompressTypeEnum`
- `common.extension.ExtensionLoader`

## RpcMessage 协议结构

项目中的 `RpcMessage` Java 对象只保存这几个字段：

```java
private byte messageType;
private byte codec;
private byte compress;
private int requestId;
private Object data;
```

但是实际写入网络的协议帧不只这些。`magic number`、`version`、`full length` 是在 `RpcMessageEncoder` 中额外写入的协议头字段。

协议整体结构如下：

| 字段 | 长度 | 偏移量 | 说明 |
| --- | --- | --- | --- |
| magic number | 4B | 0-3 | 魔数，标识当前字节流属于本 RPC 协议 |
| version | 1B | 4 | 协议版本号 |
| full length | 4B | 5-8 | 整个消息长度，包含协议头和 body |
| message type | 1B | 9 | 请求、响应、心跳请求、心跳响应 |
| codec | 1B | 10 | 序列化方式 |
| compress | 1B | 11 | 压缩方式 |
| requestId | 4B | 12-15 | 协议头中的请求编号 |
| body | 不固定 | 16 之后 | 序列化并压缩后的请求或响应对象 |

固定协议头长度是 16 字节，对应：

```java
RpcConstants.HEAD_LENGTH = 16;
```

## 协议字段作用

## magic number

项目中魔数定义为：

```java
public static final byte[] MAGIC_NUMBER = {
    (byte) 'g', (byte) 'r', (byte) 'p', (byte) 'c'
};
```

作用是快速判断收到的字节流是不是当前 RPC 框架的协议数据。

解码时，`RpcMessageDecoder.checkMagicNumber()` 会读取前 4 个字节，并和 `RpcConstants.MAGIC_NUMBER` 比较。如果不一致，就抛出异常：

```java
throw new IllegalArgumentException("Unknown magic code: " + Arrays.toString(tmp));
```

面试表达：

> magic number 相当于协议标识，可以避免服务端把非法数据或者其他协议的数据当成 RPC 请求解析。

## version

项目中版本号定义为：

```java
public static final byte VERSION = 1;
```

作用是做协议版本兼容控制。解码时，`RpcMessageDecoder.checkVersion()` 会判断收到的版本是否等于当前框架版本。

面试表达：

> version 字段用于协议升级时做兼容控制。比如后续协议头新增字段，可以通过版本号区分新旧协议。

## full length

`full length` 表示整条消息的总长度，包括 16 字节协议头和 body 长度。

它有两个核心作用：

- 让接收方知道一条完整消息应该读多少字节。
- 配合 `LengthFieldBasedFrameDecoder` 解决 TCP 粘包半包问题。

编码时，`RpcMessageEncoder` 会先预留 4 字节长度字段，等 body 序列化压缩完成后，再回填总长度。

面试表达：

> TCP 是流式协议，没有天然消息边界，所以我在协议头中设计了 full length 字段。接收方根据这个长度字段判断当前 ByteBuf 是否包含完整消息，完整才继续解码。

## message type

项目中消息类型由 `RpcConstants` 定义：

```java
REQUEST_TYPE = 1;
RESPONSE_TYPE = 2;
HEARTBEAT_REQUEST_TYPE = 3;
HEARTBEAT_RESPONSE_TYPE = 4;
```

作用是告诉接收方 body 应该按什么语义处理：

- `REQUEST_TYPE`：body 反序列化为 `RpcRequest`。
- `RESPONSE_TYPE`：body 反序列化为 `RpcResponse`。
- `HEARTBEAT_REQUEST_TYPE`：心跳请求，数据是 `PING`。
- `HEARTBEAT_RESPONSE_TYPE`：心跳响应，数据是 `PONG`。

## codec

`codec` 表示序列化方式。项目中通过 `SerializationTypeEnum` 定义：

```java
KYRO((byte) 0x01, "kryo")
PROTOSTUFF((byte) 0x02, "protostuff")
HESSIAN((byte) 0x03, "hessian")
```

客户端普通 RPC 请求中使用：

```java
.codec(SerializationTypeEnum.HESSIAN.getCode())
```

服务端普通响应中也使用 Hessian。

面试表达：

> codec 字段让接收方知道应该用哪种序列化器反序列化 body。项目中序列化器不是写死的，而是通过自定义 SPI 从配置文件中按名称加载。

## compress

`compress` 表示压缩方式。项目中目前只支持：

```java
GZIP((byte) 0x01, "gzip")
```

编码时先序列化，再压缩。解码时先解压，再反序列化。

面试表达：

> compress 字段让协议具备扩展性。当前项目默认使用 Gzip，后续可以扩展 Snappy、LZ4 等更适合 RPC 场景的压缩算法。

## requestId

协议头里有一个 `int requestId`，由 `RpcMessageEncoder` 里的 `AtomicInteger` 自动生成：

```java
private static final AtomicInteger ATOMIC_INTEGER = new AtomicInteger(0);
out.writeInt(ATOMIC_INTEGER.getAndIncrement());
```

需要注意：项目真正用于客户端匹配响应的是 `RpcRequest` 和 `RpcResponse` body 里的 `String requestId`，它由 `RpcClientProxy` 使用 `UUID.randomUUID()` 生成。

也就是说，当前项目里存在两个 requestId 概念：

- 协议头 `RpcMessage.requestId`：int 类型，目前主要作为消息头编号，实际业务匹配没有使用。
- body 中的 `RpcRequest.requestId`：String 类型，客户端用它放入 `UnprocessedRequests`，服务端响应时原样带回，客户端再根据它完成 `CompletableFuture`。

面试中可以把这个点讲成优化空间：

> 当前项目的响应匹配主要依赖 body 中的 requestId，协议头 requestId 没有被充分利用。优化时可以统一 requestId，只在协议头中维护请求编号，减少重复字段，并让响应匹配直接基于协议头完成。

## body

body 是真正的业务数据。

普通请求中，body 是：

```java
RpcRequest
```

普通响应中，body 是：

```java
RpcResponse
```

编码顺序：

```text
Java 对象 -> serialize -> compress -> body bytes
```

解码顺序：

```text
body bytes -> decompress -> deserialize -> Java 对象
```

心跳消息比较特殊。`RpcMessageEncoder` 对心跳请求和心跳响应不会序列化 body，只发送固定协议头；`RpcMessageDecoder` 根据 `messageType` 直接把 data 设置为 `PING` 或 `PONG`。

## RpcMessageEncoder 编码流程

`RpcMessageEncoder` 继承：

```java
MessageToByteEncoder<RpcMessage>
```

它的作用是把内存中的 `RpcMessage` 编码成 Netty 可以发送的 `ByteBuf`。

核心流程如下：

1. 写入 4 字节魔数：

```java
out.writeBytes(RpcConstants.MAGIC_NUMBER);
```

2. 写入 1 字节版本号：

```java
out.writeByte(RpcConstants.VERSION);
```

3. 预留 4 字节 full length：

```java
out.writerIndex(out.writerIndex() + 4);
```

因为此时 body 还没有完成序列化压缩，所以总长度还不知道。

4. 写入消息类型：

```java
out.writeByte(messageType);
```

5. 写入序列化方式：

```java
out.writeByte(rpcMessage.getCodec());
```

6. 写入压缩方式：

```java
out.writeByte(rpcMessage.getCompress());
```

7. 写入协议头 requestId：

```java
out.writeInt(ATOMIC_INTEGER.getAndIncrement());
```

8. 如果不是心跳消息，就处理 body：

```java
String codecName = SerializationTypeEnum.getName(rpcMessage.getCodec());
Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
        .getExtension(codecName);
bodyBytes = serializer.serialize(rpcMessage.getData());
```

9. 对序列化结果进行压缩：

```java
String compressName = CompressTypeEnum.getName(rpcMessage.getCompress());
Compress compress = ExtensionLoader.getExtensionLoader(Compress.class)
        .getExtension(compressName);
bodyBytes = compress.compress(bodyBytes);
```

10. 计算 full length：

```java
int fullLength = RpcConstants.HEAD_LENGTH + bodyBytes.length;
```

11. 写入 body：

```java
out.writeBytes(bodyBytes);
```

12. 回填 full length：

```java
int writeIndex = out.writerIndex();
out.writerIndex(writeIndex - fullLength + RpcConstants.MAGIC_NUMBER.length + 1);
out.writeInt(fullLength);
out.writerIndex(writeIndex);
```

面试表达：

> 编码器的核心是先写固定协议头，full length 先占位，等 body 序列化压缩完成后再计算总长度并回填。这样接收方就能通过长度字段准确拆分消息。

## RpcMessageDecoder 解码流程

`RpcMessageDecoder` 继承：

```java
LengthFieldBasedFrameDecoder
```

它的作用分两层：

- 第一层：交给 `LengthFieldBasedFrameDecoder` 根据 full length 切出完整帧。
- 第二层：项目自己解析协议头和 body，构造 `RpcMessage`。

核心流程如下：

1. 调用父类解码，先拿到完整消息帧：

```java
Object decoded = super.decode(ctx, in);
```

如果数据不完整，父类不会向后传递半包。

2. 判断是否是 `ByteBuf`：

```java
if (decoded instanceof ByteBuf) {
    ByteBuf frame = (ByteBuf) decoded;
}
```

3. 校验长度至少大于协议头长度：

```java
if (frame.readableBytes() >= RpcConstants.TOTAL_LENGTH) {
    return decodeFrame(frame);
}
```

4. 校验魔数：

```java
checkMagicNumber(in);
```

5. 校验版本：

```java
checkVersion(in);
```

6. 读取协议头字段：

```java
int fullLength = in.markReaderIndex().readInt();
byte messageType = in.readByte();
byte codecType = in.readByte();
byte compressType = in.readByte();
int requestId = in.readInt();
```

7. 构造 `RpcMessage`：

```java
RpcMessage rpcMessage = RpcMessage.builder()
        .codec(codecType)
        .requestId(requestId)
        .messageType(messageType)
        .build();
```

8. 如果是心跳消息，直接设置 data：

```java
rpcMessage.setData(RpcConstants.PING);
rpcMessage.setData(RpcConstants.PONG);
```

9. 计算 body 长度：

```java
int bodyLength = fullLength - RpcConstants.HEAD_LENGTH;
```

10. 读取 body 字节数组：

```java
byte[] bs = new byte[bodyLength];
in.readBytes(bs);
```

11. 解压 body：

```java
Compress compress = ExtensionLoader.getExtensionLoader(Compress.class)
        .getExtension(compressName);
bs = compress.decompress(bs);
```

12. 根据 codec 获取序列化器：

```java
String codecName = SerializationTypeEnum.getName(rpcMessage.getCodec());
Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
        .getExtension(codecName);
```

13. 根据消息类型反序列化为请求或响应：

```java
if (messageType == RpcConstants.REQUEST_TYPE) {
    RpcRequest tmpValue = serializer.deserialize(bs, RpcRequest.class);
    rpcMessage.setData(tmpValue);
}

if (messageType == RpcConstants.RESPONSE_TYPE) {
    RpcResponse tmpValue = serializer.deserialize(bs, RpcResponse.class);
    rpcMessage.setData(tmpValue);
}
```

14. 释放 ByteBuf，避免内存泄漏：

```java
frame.release();
```

面试表达：

> 解码器先由 Netty 的 `LengthFieldBasedFrameDecoder` 负责拆包，拿到完整帧后再校验魔数和版本，读取协议头，最后按照 compress 和 codec 字段解压、反序列化 body，得到 `RpcRequest` 或 `RpcResponse`。

## LengthFieldBasedFrameDecoder 参数分析

项目中的构造参数是：

```java
this(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
```

对应：

```java
LengthFieldBasedFrameDecoder(
    maxFrameLength,
    lengthFieldOffset,
    lengthFieldLength,
    lengthAdjustment,
    initialBytesToStrip
)
```

## maxFrameLength

项目中是：

```java
RpcConstants.MAX_FRAME_LENGTH = 8 * 1024 * 1024;
```

表示单个消息帧最大 8MB。超过这个长度，Netty 会认为数据异常，避免内存被超大包打爆。

## lengthFieldOffset

项目中是 `5`。

原因：

- magic number 占 4 字节。
- version 占 1 字节。
- full length 紧跟在 version 后面。

所以长度字段从偏移量 5 开始。

## lengthFieldLength

项目中是 `4`。

因为 full length 是 int，占 4 字节。

## lengthAdjustment

项目中是 `-9`。

原因是 full length 字段表示整条消息长度，包含：

```text
magic number + version + full length + message type + codec + compress + requestId + body
```

当 Netty 读取到 length 字段时，已经越过了：

```text
4B magic number + 1B version + 4B full length = 9B
```

而 length 字段本身的值又包含这 9B，所以需要用 `-9` 修正剩余长度。

## initialBytesToStrip

项目中是 `0`。

表示不丢弃协议头，完整帧交给后续 `decodeFrame()` 自己解析。因为项目还要自己校验 magic number、version，并读取 message type、codec、compress 等字段。

## 如何解决粘包半包

TCP 是字节流协议，不保证一次 `writeAndFlush()` 对应接收方一次完整读取。

可能出现：

- 粘包：多个 RPC 消息被合并到一次读取中。
- 半包：一个 RPC 消息被拆成多次读取。

项目的解决方式是：

1. 协议头中加入 `full length`。
2. `RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`。
3. Netty 根据 length 字段判断完整帧边界。
4. 数据不足时继续等待。
5. 数据足够时切出完整帧再交给 `decodeFrame()`。

面试表达：

> 粘包半包的本质是 TCP 没有消息边界。我的解决方式是在协议头里加入 full length 字段，然后用 Netty 的 `LengthFieldBasedFrameDecoder` 按长度字段拆帧。它会自动缓存半包，也能从粘在一起的数据里拆出多个完整帧，所以业务解码逻辑拿到的一定是一条完整 RPC 消息。

## 序列化扩展方式

项目抽象了序列化接口：

```java
@SPI
public interface Serializer {
    byte[] serialize(Object obj);
    <T> T deserialize(byte[] bytes, Class<T> clazz);
}
```

因为接口上有 `@SPI`，所以可以通过自定义 `ExtensionLoader` 加载实现类。

配置文件是：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.serialize.Serializer
```

内容是：

```text
kyro=simple.serialize.kyro.KryoSerializer
protostuff=simple.serialize.protostuff.ProtostuffSerializer
hessian=simple.serialize.hessian.HessianSerializer
```

编码和解码时会根据 `codec` 字节码找到对应名称，再通过 SPI 获取实现：

```java
String codecName = SerializationTypeEnum.getName(rpcMessage.getCodec());
Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
        .getExtension(codecName);
```

## Hessian

实现类：

```java
simple.serialize.hessian.HessianSerializer
```

核心实现：

- 序列化：`HessianOutput.writeObject(obj)`
- 反序列化：`HessianInput.readObject()`

项目普通请求和响应默认使用 Hessian：

```java
SerializationTypeEnum.HESSIAN.getCode()
```

优点：

- 使用简单。
- 二进制协议，体积通常比 JDK 原生序列化更小。
- 对 Java 对象支持比较直接。

面试表达：

> 项目默认 RPC 请求和响应使用 Hessian，它是二进制序列化方式，使用成本比较低，适合教学型 RPC 框架快速落地。

## Kryo

实现类：

```java
simple.serialize.kyro.KryoSerializer
```

Kryo 的特点是性能较高、序列化结果较紧凑，但 `Kryo` 实例本身不是线程安全的。

项目中使用：

```java
private final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
    Kryo kryo = new Kryo();
    kryo.register(RpcResponse.class);
    kryo.register(RpcRequest.class);
    return kryo;
});
```

这样每个线程都有自己的 Kryo 实例，避免多线程共享导致并发问题。

面试表达：

> Kryo 性能好，但它不是线程安全的，所以项目中用 `ThreadLocal<Kryo>` 给每个线程保存独立实例。同时预注册 `RpcRequest` 和 `RpcResponse`，减少类信息写入，提高性能。

## Protostuff

实现类：

```java
simple.serialize.protostuff.ProtostuffSerializer
```

核心逻辑：

- 使用 `RuntimeSchema.getSchema(clazz)` 动态生成 schema。
- 使用 `ProtostuffIOUtil.toByteArray()` 序列化。
- 使用 `ProtostuffIOUtil.mergeFrom()` 反序列化。

优点：

- 序列化性能较好。
- 不需要手写 `.proto` 文件，使用运行时 schema。

项目中的风险点：

```java
private static final LinkedBuffer BUFFER =
        LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
```

这个 `BUFFER` 是静态共享的，多线程下可能有并发安全问题。更好的方式是每次序列化创建局部 `LinkedBuffer`，或者使用 `ThreadLocal<LinkedBuffer>`。

面试表达：

> Protostuff 性能不错，而且不需要写 proto 文件。但当前项目里 `LinkedBuffer` 是 static final 共享对象，多线程下有风险，优化时可以改成局部变量或 ThreadLocal。

## Gzip 压缩

项目抽象了压缩接口：

```java
@SPI
public interface Compress {
    byte[] compress(byte[] bytes);
    byte[] decompress(byte[] bytes);
}
```

配置文件是：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.compress.Compress
```

内容是：

```text
gzip=simple.compress.gzip.GzipCompress
```

实现类：

```java
simple.compress.gzip.GzipCompress
```

压缩使用：

```java
GZIPOutputStream
```

解压使用：

```java
GZIPInputStream
```

Gzip 的作用：

- 降低网络传输体积。
- 对较大请求或响应更明显。
- 通过 `compress` 字段保留后续扩展空间。

需要注意：

> 小对象压缩可能不一定划算，因为 Gzip 有额外 CPU 开销，也有压缩格式头部开销。真实生产环境可以根据 body 大小设置阈值，比如超过 1KB 或 4KB 才压缩。

面试表达：

> 项目在序列化后做 Gzip 压缩，目的是减少网络传输数据量。压缩方式通过 `Compress` SPI 扩展，后续可以替换为 Snappy、LZ4 这种速度更快、适合 RPC 场景的算法。

## 当前协议设计的问题和优化方向

## 1. 解码器中压缩类型读取存在疑似 bug

源码中读取了：

```java
byte codecType = in.readByte();
byte compressType = in.readByte();
```

但后面获取压缩名称时写的是：

```java
String compressName = CompressTypeEnum.getName(codecType);
```

这里应该使用：

```java
String compressName = CompressTypeEnum.getName(compressType);
```

当前项目默认 Hessian 的 code 是 `0x03`，Gzip 的 code 是 `0x01`。如果走到这个逻辑，可能会因为用序列化类型编码去查压缩类型，导致获取不到压缩器。

面试应对：

> 这是我复盘项目时发现的一个实现问题。协议设计本身是 codec 和 compress 分开的，但解码器取压缩器时应该根据 compressType，而不是 codecType。这个属于代码实现 bug，修复很明确。

## 2. 协议头 requestId 和业务 requestId 重复

当前项目有两个 requestId：

- 协议头：`RpcMessage.requestId`，int 类型，由编码器原子自增生成。
- 请求体：`RpcRequest.requestId`，String 类型，由客户端代理用 UUID 生成。

真正匹配响应用的是请求体里的 UUID。

优化方向：

- 统一使用协议头 requestId。
- 或者删除协议头 requestId，明确响应匹配只基于 body requestId。
- 更推荐统一协议头 requestId，减少 body 冗余，让协议层承担请求响应关联职责。

## 3. full length 回填逻辑可读性一般

当前回填逻辑依赖 writerIndex 计算：

```java
out.writerIndex(writeIndex - fullLength + RpcConstants.MAGIC_NUMBER.length + 1);
out.writeInt(fullLength);
out.writerIndex(writeIndex);
```

能工作，但可读性不强。

优化方向：

- 在写 full length 前记录 `fullLengthWriterIndex`。
- 编码完成后直接回到该位置写入长度。

示例思路：

```java
int fullLengthWriterIndex = out.writerIndex();
out.writerIndex(fullLengthWriterIndex + 4);
...
out.setInt(fullLengthWriterIndex, fullLength);
```

## 4. 协议缺少状态码、错误类型等头部扩展字段

当前错误信息主要在 `RpcResponse` body 中。

优化方向：

- 协议头增加状态位或 flags。
- 支持是否压缩、是否心跳、是否单向请求等标志。
- 错误场景可以更快定位，不一定必须反序列化 body。

## 5. 缺少校验和或签名

当前协议通过 magic number 判断协议类型，但不能判断 body 是否损坏，也不能防止篡改。

优化方向：

- 增加 CRC32 或 checksum。
- 在安全要求高的场景增加签名或认证字段。

## 6. 序列化方式和压缩方式没有降级处理

如果 `codec` 或 `compress` 找不到对应实现，当前会直接异常。

优化方向：

- 对未知 codec/compress 给出更明确异常。
- 客户端服务端启动时做能力协商。
- 提供默认序列化和默认压缩策略。

## 7. Protostuff 的 LinkedBuffer 存在线程安全风险

当前 `ProtostuffSerializer` 使用静态共享 `LinkedBuffer`。

优化方向：

- 改成方法局部变量。
- 或改成 `ThreadLocal<LinkedBuffer>`。

## 8. Gzip 不一定适合所有 RPC 小包

RPC 请求通常可能很小。对小对象强制 Gzip，可能 CPU 成本大于网络收益。

优化方向：

- 设置压缩阈值。
- 小包不压缩。
- 大包压缩。
- 引入 Snappy、LZ4 这类更偏向速度的压缩算法。

## 9. 缺少协议版本兼容策略

项目中只是简单判断：

```java
if (version != RpcConstants.VERSION) {
    throw new RuntimeException("version isn't compatiable" + version);
}
```

优化方向：

- 支持多版本协议解码器。
- 使用 flags 或 header length 支持协议头扩展。
- 对低版本协议做兼容解析。

## 10. 异常处理可以更精细

`RpcMessageEncoder` 捕获异常后只记录日志，没有向上抛出或关闭连接。

优化方向：

- 编码失败时关闭 Channel。
- 将异常传递给调用方的 `CompletableFuture`。
- 对序列化异常、压缩异常、协议异常做分类处理。

## 高频面试问题

## 1. 你这个 RPC 协议是怎么设计的？

参考回答：

> 我采用的是固定协议头加可变 body 的设计。固定头长度是 16 字节，包括 magic number、version、full length、message type、codec、compress 和 requestId。body 存放真正的业务对象，也就是 `RpcRequest` 或 `RpcResponse`，会先序列化再压缩。这样设计的好处是协议解析简单，full length 可以解决消息边界问题，codec 和 compress 字段也保留了扩展能力。

高概率追问：

- 为什么需要 magic number？
- full length 为什么要包含协议头？
- requestId 放在协议头和 body 里有什么区别？

## 2. 为什么要自定义协议，不直接用 Java 序列化对象传输？

参考回答：

> 因为 RPC 框架需要在网络层明确消息边界、消息类型、序列化方式和压缩方式。如果直接传 Java 对象，扩展性和跨语言能力都比较差，也不好处理粘包半包。自定义协议之后，框架可以统一处理请求、响应、心跳、序列化、压缩和版本兼容。

高概率追问：

- 自定义协议会不会增加复杂度？
- 如果协议升级，你怎么兼容旧客户端？

## 3. 你的项目怎么解决 TCP 粘包半包？

参考回答：

> TCP 是字节流协议，发送方一次写入和接收方一次读取没有一一对应关系。项目在协议头中加入 full length 字段，然后让 `RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`，设置 lengthFieldOffset 为 5，lengthFieldLength 为 4，lengthAdjustment 为 -9。Netty 会根据长度字段自动缓存半包、拆分粘包，保证业务解码拿到的是完整消息帧。

高概率追问：

- `LengthFieldBasedFrameDecoder` 的参数分别是什么意思？
- 为什么 lengthAdjustment 是 -9？

## 4. `LengthFieldBasedFrameDecoder(MAX, 5, 4, -9, 0)` 怎么解释？

参考回答：

> `MAX` 是最大帧长度，项目里是 8MB。`5` 表示长度字段偏移量，因为前面有 4 字节魔数和 1 字节版本号。`4` 表示长度字段占 4 字节。`-9` 是因为 full length 表示整条消息长度，而读取到长度字段时已经经过了 magic、version 和 full length 共 9 字节，所以要减掉 9。最后的 `0` 表示不剥离协议头，后续还要自己校验魔数和版本。

高概率追问：

- 如果 initialBytesToStrip 设置为 16 会怎样？
- 如果 full length 只表示 body 长度，参数该怎么改？

## 5. 编码流程是什么？

参考回答：

> 编码器先写魔数和版本号，然后预留 full length，再写 message type、codec、compress 和 requestId。如果不是心跳消息，就根据 codec 获取序列化器，把 `RpcRequest` 或 `RpcResponse` 序列化成字节数组，再根据 compress 获取压缩器进行压缩。最后写入 body，并回填 full length。

高概率追问：

- 为什么 full length 要最后回填？
- 序列化和压缩的顺序能不能反过来？

## 6. 解码流程是什么？

参考回答：

> 解码时先由 `LengthFieldBasedFrameDecoder` 根据 full length 切出完整帧，然后项目自己的 `decodeFrame()` 校验 magic number 和 version，读取 full length、message type、codec、compress、requestId。如果有 body，就先根据 compress 解压，再根据 codec 获取序列化器，最后按 message type 反序列化成 `RpcRequest` 或 `RpcResponse`。

高概率追问：

- 为什么解码要先解压再反序列化？
- 如果 magic number 不匹配怎么处理？

## 7. 你项目中有哪些序列化方式？怎么扩展？

参考回答：

> 项目里定义了 `Serializer` 接口，提供 `serialize()` 和 `deserialize()` 两个方法，并用自定义 `@SPI` 标记。当前实现有 Hessian、Kryo 和 Protostuff。配置文件放在 `META-INF/simple.serialize.Serializer`，比如 `hessian=simple.serialize.hessian.HessianSerializer`。编解码时根据协议头的 codec 字段找到名称，再通过 `ExtensionLoader` 加载对应实现。新增序列化方式只需要实现接口、更新枚举和 SPI 配置文件。

高概率追问：

- 为什么要用 SPI？
- Hessian、Kryo、Protostuff 有什么区别？

## 8. Hessian、Kryo、Protostuff 分别有什么特点？

参考回答：

> Hessian 使用简单，是二进制序列化方式，项目默认请求响应用它。Kryo 性能比较好，但不是线程安全的，所以项目里用 `ThreadLocal<Kryo>`。Protostuff 性能也比较好，不需要手写 proto 文件，可以运行时生成 schema，但当前实现里静态共享 `LinkedBuffer` 有线程安全风险，需要优化。

高概率追问：

- Kryo 为什么要用 ThreadLocal？
- Protostuff 当前实现有什么风险？

## 9. 为什么要压缩？Gzip 有什么问题？

参考回答：

> 压缩主要是为了减少网络传输体积，尤其是请求或响应比较大时效果明显。项目里抽象了 `Compress` 接口，目前实现是 Gzip。不过 Gzip 对小包不一定划算，因为会增加 CPU 开销和额外格式头。优化时可以设置压缩阈值，小包不压缩，大包再压缩，也可以引入 Snappy 或 LZ4。

高概率追问：

- 压缩应该在序列化前还是序列化后？
- 所有请求都压缩合理吗？

## 10. 当前协议有什么可以优化的地方？

参考回答：

> 第一，解码器里获取压缩器时应该使用 compressType，目前代码疑似误用了 codecType。第二，协议头 requestId 和 body 中的 requestId 重复，最好统一。第三，Gzip 可以加压缩阈值，避免小包也压缩。第四，Protostuff 的静态 `LinkedBuffer` 可以改成局部变量或 ThreadLocal。第五，协议可以增加 header length、flags、checksum 等字段，为版本兼容、扩展和完整性校验做准备。

高概率追问：

- 你会优先修哪个问题？
- 如果要做协议兼容，你怎么设计？

## 可背诵面试回答

> 这个 RPC 项目在网络传输层自定义了一套协议，整体是固定 16 字节协议头加可变 body。协议头包括 4 字节 magic number、1 字节 version、4 字节 full length、1 字节 message type、1 字节 codec、1 字节 compress 和 4 字节 requestId。magic number 用于识别协议，version 用于后续协议兼容，full length 用于确定消息边界，message type 区分请求、响应和心跳，codec 和 compress 分别表示序列化方式和压缩方式。客户端发送请求时，`RpcMessageEncoder` 会把 `RpcRequest` 先序列化，再用 Gzip 压缩，然后写入协议头和 body。服务端接收时，`RpcMessageDecoder` 先通过 `LengthFieldBasedFrameDecoder` 根据 full length 切出完整消息帧，解决 TCP 粘包半包问题，再校验魔数和版本，读取协议头，最后解压并反序列化成 `RpcRequest`。响应返回客户端也是同样流程，只是 body 变成 `RpcResponse`。序列化和压缩都通过自定义 SPI 扩展，目前支持 Hessian、Kryo、Protostuff 和 Gzip。这个设计让协议结构清晰，也让序列化、压缩、消息类型这些能力都具备可扩展性。
