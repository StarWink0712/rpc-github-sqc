package simple.serialize.kyro;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.extern.slf4j.Slf4j;
import simple.remoting.dto.RpcRequest;
import simple.remoting.dto.RpcResponse;
import simple.serialize.Serializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * KryoSerializer
 *
 * @author 执笔画棠
 * @date 2025/10/18 12:27
 **/
@Slf4j
public class KryoSerializer implements Serializer {

    /**
     * 这段注释解释了为什么使用 ThreadLocal：因为 Kryo 实例本身不是线程安全的。
     * 在多线程环境下共享同一个 Kryo 实例会导致并发问题（如数据错乱）。
     * 因此，使用 ThreadLocal 为每个线程创建一个独立的 Kryo 实例副本，从而保证线程安全。
     */
    private final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        // withInitial 方法接收一个 Supplier，当一个线程首次调用 .get() 方法时，此代码块会执行，用于创建初始值
        Kryo kryo = new Kryo();
        // 预先注册需要序列化/反序列化的类。这是一种 Kryo 的性能优化手段。
        // 注册后，Kryo 会用一个整数 ID 来代替完整的类名，从而大大减小序列化后的大小。
        kryo.register(RpcResponse.class);
        kryo.register(RpcRequest.class);
        // 返回为当前线程创建和配置好的 Kryo 实例
        return kryo;
    });

    @Override
    public byte[] serialize(Object obj) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             // Output 是 Kryo 提供的对输出流的封装，性能更高。
             Output output = new Output(byteArrayOutputStream)) {
            // 从 ThreadLocal 中获取当前线程专属的 Kryo 实例
            Kryo kryo = kryoThreadLocal.get();
            // Object->byte:将对象序列化为byte数组
            kryo.writeObject(output, obj);
            output.flush();
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            log.error("Serialization failed", e);
            throw new SerializeException("Serialization failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
             // Input 是 Kryo 提供的对输入流的高性能封装
             Input input = new Input(byteArrayInputStream)) {
            Kryo kryo = kryoThreadLocal.get();
            // byte->Object:从byte数组中反序列化出对对象
            return kryo.readObject(input, clazz);
        } catch (Exception e) {
            log.error("Deserialization failed", e);
            throw new SerializeException("Deserialization failed", e);
        }
    }

    //kryo序列化自己用的异常类
    public class SerializeException extends RuntimeException {
        public SerializeException(String message) {
            super(message);
        }

        public SerializeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
