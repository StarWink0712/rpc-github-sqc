package common.utils;

/**
 * StringUtil 字符串工具类
 *
 * @author 执笔画棠
 * @date 2025/10/15 13:54
 **/
public class StringUtil {
    //判断字符串是否为空或只包含空格
    public static boolean isBlank(String s) {
        if (s == null || s.length() == 0) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

}
