package common.utils;

import java.util.Collection;

/**
 * CollectionUtil
 *
 * @author 执笔画棠
 * @date 2025/10/19 16:17
 **/
public class CollectionUtil {
    //工具类判断集合是否为空
    public static boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }
}
