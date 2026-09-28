package simple.serialize.hessian;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import common.exception.SerializeException;
import simple.serialize.Serializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * HessianSerializer 序列化方式1
 *
 * @author 执笔画棠
 * @date 2025/10/18 12:15
 **/
public class HessianSerializer implements Serializer {

    @Override
    public byte[] serialize(Object obj) {

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            HessianOutput hessianOutput = new HessianOutput(byteArrayOutputStream);
            //传入一个obj对象，hess会将其序列化成hessian格式的二进制数据
            hessianOutput.writeObject(obj);
            //将stream流中存的结果转为字节数组返回
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new SerializeException("hessian failed");
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {

        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes)) {
            //也是先用一个inputstream流存bytes字节数组，然后给hessianinput
            HessianInput hessianInput = new HessianInput(byteArrayInputStream);
            //新建一个对象类，hess会返序列化读到对应的类给o，最后转换类型返回
            Object o = hessianInput.readObject();
            //使用传入的 clazz 对象（目标类型的类对象）的 cast 方法
            //这个方法会尝试将反序列化得到的对象 o 安全地转换成 T 类型
            return clazz.cast(o);
        } catch (IOException e) {
            throw new SerializeException("hessian deserialize failed");
        }
    }
}
