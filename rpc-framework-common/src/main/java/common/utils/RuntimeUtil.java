package common.utils;

/**
 * RuntimeUtil 运行时工具类
 *
 * @author 执笔画棠
 * @date 2025/10/16 17:06
 **/
public class RuntimeUtil {
    //获取cpu的核心数
    public static int cpus() {
        return Runtime.getRuntime().availableProcessors();
    }
}
