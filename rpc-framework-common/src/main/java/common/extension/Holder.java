package common.extension;

/**
 * Holder 线程安全值的持有者
 *
 * @author 执笔画棠
 * @date 2025/10/14 21:25
 **/
public class Holder<T> {
    private volatile T value;

    public T get() {
        return value;
    }


    public void set(T instance) {
        this.value = value;
    }
}
