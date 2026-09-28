package common.factory;

import common.extension.Holder;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SingletonFactory 单例工厂实现类
 * 这个是核心工具类，提供三种获取实例的方法
 * 1是自定义构造函数，适用于需要特殊构造逻辑的类
 * 2是多一个额外初始化的操作
 * 3是直接反射创建，适用于无特殊初始化需求的类
 *
 * @author 执笔画棠
 * @date 2025/10/14 18:49
 **/
@Slf4j
public final class SingletonFactory {

    //用线程安全的map集合存单例对象，key是类的全限定名，value是单例对象，旧方法用在 getInstanceOld 方法中。
    private static final Map<String, Object> OBJECT_MAP = new ConcurrentHashMap<>();
    //同步锁，用来保证线程安全
    private static final Object lock = new Object();

    //单例对象缓存2，用holder类保存单例对象，用在三个新的 getInstance 方法中，此方法解决了java内存模型下可能的可见性问题。
    private static final Map<String, Holder<Object>> OBJECT_MAP_NEW = new ConcurrentHashMap<>();
//    private static final Map<String, Holder<Object>> OBJECT_MAP_NEW = new HashMap<>();

    //构造方法
    private SingletonFactory() {

    }

    /**
     * 获取单例对象的方法一
     * 允许传入一个自定义的创建逻辑 (Supplier)。
     * 这意味着对象可以有复杂的构造过程，比如需要参数、需要从其他工厂获取等。
     * 这极大地增强了通用性。
     *
     * @param constructor 用于创建实例的构造函数
     * @param c           实例的类类型
     * @return 单例对象
     * 在extensionloader中调用，传递进来的c是
     * 通过name获得的类的字节码文件
     */
    public static <T> T getInstance(Supplier<T> constructor, Class<T> c) {
        //如果通过name获得的类的字节码文件是空，则抛出异常
        if (c == null) {
            throw new IllegalArgumentException("class cannot be null");
        }

        //获取类的全限定名
        String key = c.getName();
        //通过类的全限定名从缓存中获取holder
        Holder<Object> holder = OBJECT_MAP_NEW.get(key);
        //holder保证了可见性，从而不会使用没有初始化的对象
        //cast方法就是把holder.get得到的对象从obj转变到c类型
        if (holder != null && holder.get() != null) {
            return c.cast(holder.get());
        }

        //如果holder为空就表明没有缓存，需要建立单例
        //加同步锁，确保只有一个线程创建单例
        synchronized (lock) {
            //第二次检查，防止其他线程在加锁前创建了holder
            holder = OBJECT_MAP_NEW.computeIfAbsent(key, k -> new Holder<>());

            //创建实例,在锁内仍然没有holder存在，则可以放心创建实例了
            if (holder.get() == null) {
                try {
                    //创建对象，调用supplier函数式接口的get方法
                    T instance = constructor.get();
                    holder.set(instance);
                } catch (Exception e) {
                    throw new RuntimeException("创建实例失败", e);
                }
            }

        }
        //返回实例
        return c.cast(holder.get());
    }

    /**
     * 获取单例对象的方法二
     * 允许对象在创建后，执行一个额外的初始化操作 (Consumer)。
     * 比如创建一个线程池后，需要调用 init() 方法设置一些参数。
     *
     * @param initConsumer
     * @param c
     * @param <T>
     * @return
     */
    public static <T> T getInstance(Consumer<T> initConsumer, Class<T> c) {
        if (c == null) {
            throw new IllegalArgumentException("class cannot be null");
        }

        String key = c.getName();

        Holder<Object> holder = OBJECT_MAP_NEW.get(key);

        if (holder != null && holder.get() != null) {
            return c.cast(holder.get());
        }

        //如果holder不存在，则创建单例存到holder中并返回
        synchronized (lock) {
            //如果map里面没有这个key，就去执行new holder这个操作
            holder = OBJECT_MAP_NEW.computeIfAbsent(key, k -> new Holder<>());

            if (holder.get() == null) {
                try {
                    T instance = c.getDeclaredConstructor().newInstance();
                    initConsumer.accept(instance);
                    holder.set(instance);
                } catch (Exception e) {
                    throw new RuntimeException("创建单例失败", e);
                }
            }
        }
        return c.cast(holder.get());
    }

    public static <T> T getInstance(Class<T> c) {


        if (c == null) {
            throw new IllegalArgumentException("Class cannot be null");
        }
        String key = c.getName();

        log.info("正在创建实例[{}]", key);
        // 1. 第一次检查：快速读取缓存（无锁）
        Holder<Object> holder = OBJECT_MAP_NEW.get(key);
        if (holder != null && holder.get() != null) {
            // 1.1 holder保证了可见性，从而不会使用没有初始化的对象
            return c.cast(holder.get());
        }

        // 2. 同步块：确保只有一个线程创建实例
        synchronized (lock) {
            // 3. 第二次检查：防止其他线程已创建holder
            holder = OBJECT_MAP_NEW.computeIfAbsent(key, k -> new Holder<>());

            // 4. 创建实例（此处不需要再次检查holder.get()，因为锁保证了互斥性）
            if (holder.get() == null) {
                try {
                    Constructor<T> constructor = c.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    T instance = constructor.newInstance();


                    if (instance == null) {
                        log.info("instance实例就是空的");
                    }
                    // 4.2 放入到map里面
                    log.info("实例[{}]", instance);
                    holder.set(instance);
                } catch (NoSuchMethodException e) {
                    System.err.println("找不到无参构造函数: " + e.getMessage());
                } catch (InstantiationException e) {
                    System.err.println("无法实例化（可能是抽象类或接口）: " + e.getMessage());
                } catch (IllegalAccessException e) {
                    System.err.println("访问权限不足: " + e.getMessage());
                } catch (InvocationTargetException e) {
                    System.err.println("构造函数内部抛出异常: " + e.getCause());
                    e.getCause().printStackTrace();
                }
//                catch (Exception e) {
//                    throw new RuntimeException("创建示例失败", e);
//                }
            }
        }
        return c.cast(holder.get());
    }

    /**
     * @param c :
     * @description:直接用反射创建单例对象
     * @date: 2025/10/14 22:06
     * @author: 执笔画棠
     * @return: T
     **/
    public static <T> T getInstanceOld(Class<T> c) {
        if (c == null) {
            throw new IllegalArgumentException();
        }
        String key = c.toString();
        if (OBJECT_MAP.containsKey(key)) {
            return c.cast(OBJECT_MAP.get(key));
        } else {
            synchronized (lock) {
                if (!OBJECT_MAP.containsKey(key)) {
                    try {
                        T instance = c.getDeclaredConstructor().newInstance();
                        OBJECT_MAP.put(key, instance);
                        return instance;
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                             | NoSuchMethodException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                } else {
                    return c.cast(OBJECT_MAP.get(key));
                }
            }
        }
    }
}

