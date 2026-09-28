package simple.serialize.protostuff;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;
import simple.serialize.Serializer;

/**
 * ProtostuffSerializer
 *
 * @author 执笔画棠
 * @date 2025/10/18 12:43
 **/
public class ProtostuffSerializer implements Serializer {

    /**
     * 这个注释解释了设计意图：为了避免每次序列化时都重新分配缓冲区空间，从而提高性能。
     * LinkedBuffer 是 Protostuff 用于序列化的缓冲区。
     * ！！！重要警告：将缓冲区定义为 'static final' 意味着所有线程将共享这同一个缓冲区实例。
     * 这在多线程环境下是一个严重的设计缺陷，会导致并发问题。
     */
    private static final LinkedBuffer BUFFER = LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);


    @Override
    public byte[] serialize(Object obj) {

        //获取待序列化对象的class对象
        Class<?> clazz = obj.getClass();
        //运行时动态的为指定的class生成一个schema
        //schema描述了对象的结构，指导proto完成序列化与反序列化
        Schema schema = RuntimeSchema.getSchema(clazz);
        //声明一个字节数组存结果
        byte[] bytes;

        //核心工具方法，将对象根据schema序列化到知道的缓冲区，并返回给最终的字节数组
        try {
            bytes = ProtostuffIOUtil.toByteArray(obj, schema, BUFFER);
        } finally {
            //无论成功与否都重制缓冲区以便下次使用
            BUFFER.clear();
        }
        return bytes;
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        // 使用 Schema 创建一个目标类的空实例。
        // ！！！重要前提：这要求目标类（clazz）必须有一个无参的构造函数。
        T obj = schema.newMessage();
        // Protostuff 的核心反序列化方法。
        // 它从字节数组（bytes）中读取数据，并根据 Schema 将数据填充（合并）到刚刚创建的空对象（obj）中。
        ProtostuffIOUtil.mergeFrom(bytes, obj, schema);
        return obj;
    }
}
