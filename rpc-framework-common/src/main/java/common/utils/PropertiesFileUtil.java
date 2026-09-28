package common.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * PropertiesFileUtil 自定义读取属性工具类
 *
 * @author 执笔画棠
 * @date 2025/10/15 20:01
 **/
@Slf4j
public class PropertiesFileUtil {

    private PropertiesFileUtil() {
    }

    /**
     * 读取属性文件
     *
     * @param fileName 属性文件名
     * @return 属性对象
     */
    public static Properties readPropertiesFile(String fileName) {
        //获取当前线程的类加载器
        URL url = Thread.currentThread().getContextClassLoader().getResource("");
        //初始化配置文件的路径为空字符
        String rpcConfigPath = "";
        //检查url不为空，确保资源路径有效
        if (url != null) {
            rpcConfigPath = url.getPath() + fileName;
        }
        //声明一个属性对象，用于存储读到的属性
        Properties properties = null;
        //创建文件输入流河字符流读取器
        try (InputStreamReader inputStreamReader = new InputStreamReader(new FileInputStream(rpcConfigPath), StandardCharsets.UTF_8)) {
            //实例化前面生成的属性对象
            properties = new Properties();
            //从输入流中加载属性内容，自动解析键值对存入属性文件
            properties.load(inputStreamReader);
        } catch (IOException ex) {
            log.error("occur exception when read properties file [{}]", fileName);
        }
        return properties;
    }
}
