package simple.compress.gzip;

import simple.compress.Compress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GzipCompress 压缩类型实现类
 *
 * @author 执笔画棠
 * @date 2025/10/16 21:34
 **/
public class GzipCompress implements Compress {

    //压缩缓存区的大小
    private static final int BUFFER_SIZE = 1024 * 4;

    @Override
    public byte[] compress(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("byte is null");
        }
        // out 是压缩后的字节数组,gzip 是压缩流,toByteArray() 是将压缩后的字节数组返回
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
            gzip.flush();
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("gzip compress error!");
        }
    }

    @Override
    public byte[] decompress(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes is null!");
        }
        //创建一个字节输出流对象，等着存解压后的数据，这也是为什么解压缩这里都用out的原因
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             //将待解压的字节包装成一个字节输入流，然后再包装成解压输入流，用于读取和解压gzip格式的数据
             GZIPInputStream gunzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            //创建一个字节缓存区
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            //循环读取解压缩流中的数据
            while ((n = gunzip.read(buffer)) > -1) {
                //读取数据到buffer缓存中，然后写入到输出流中
                out.write(buffer, 0, n);
            }
            //转为字节数组返回
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("decompress is error!");
        }
    }
}
