package common.extension;

import common.factory.SingletonFactory;
import common.utils.StringUtil;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * ExtensionLoader 自定义扩展加载器
 * - 要求被加载的接口必须带有 @SPI 注解
 * - 从配置文件中动态加载实现类（ META-INF/extensions/ 目录）
 * - 通过名称获取具体实现，支持多实现选择
 * - 适用于需要可插拔、可配置的核心组件 ，如服务发现器
 * 支持多种实现的（Zookeeper、Nacos等），适合用扩展加载器
 * <p>
 * SingletonFactory的实现更简单直接，对于不需要扩展的组件，使用它性能更好
 * UnprocessedRequests和ChannelProvider是框架内部组件，只需要单一实现，适合用单例工
 *
 * @author 执笔画棠
 * @date 2025/10/15 13:11
 **/
@Slf4j
public class ExtensionLoader<T> {

    //定义一下扩展类的目录，构建配置文件完整路径 ：在 loadDirectory 方法中，它与接口的全限定名拼接，形成完整的配置文件路径：
    private static final String SERVICE_DIRECTORY = "META-INF/";
//    private static final String SERVICE_DIRECTORY = "META-INF/extensions/";

    //扩展类加载器的缓存，每一个类都有一个扩展类加载器，即给每一个type都搞一个extensionloader缓存
    private static final Map<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    //扩展类的类型,就是要创建的接口的实例类型
    private final Class<?> type;

    /*
    要知道cachedInstances和cachedClasses都是为一个type服务的
    前者存这个tpye接口对应的几个name及其实例，后者对应name与其几个实现类的全限定名
     */
    //实现接口的单例的缓存，用holder封装,根据name当key缓存,
    //name` 是一个**逻辑名称**，它与某个具体的扩展实现类（`T` 接口的实现类）相关联。
    private final Map<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    //这个集合的的作用就是表示一个映射关系表，上面cachedinstance表示了name和具体的实例
    //这个集合的作用就是name和name代表的具体的实现类的全限定名，只有找到这个全限定名才能用单例工厂创建实例
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    //构造方法，传入的参数type就是要构建实例的接口
    private ExtensionLoader(Class<?> type) {
        this.type = type;
    }

    /**
     * @param type :为每一个类创建自己的扩展加载器
     * @description:
     * @date: 2025/10/15 13:38
     * @author: 执笔画棠
     * @return: common.extension.ExtensionLoader<S>
     **/
    public static <S> ExtensionLoader<S> getExtensionLoader(Class<S> type) {
        if (type == null) {
            //type不能是空
            throw new IllegalArgumentException("extension type should not be null");
        }
        if (!type.isInterface()) {
            //type应该是一个接口
            throw new IllegalArgumentException("extension type should be an interface");
        }
        if (type.getAnnotation(SPI.class) == null) {
            //类上需要有自定义的SPI注解
            throw new IllegalArgumentException("extension type should be annotated by @SPI");
        }

        //创建类加载器，从缓存中找出type这个接口的字节码文件对应的value
        ExtensionLoader<S> extensionLoader = (ExtensionLoader<S>) EXTENSION_LOADERS.get(type);
        if (extensionLoader == null) {
            //如果缓存中没有，则新建一个type这个接口的扩展加载器，new调用构造方法
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<S>(type));
            extensionLoader = (ExtensionLoader<S>) EXTENSION_LOADERS.get(type);
        }
        //返回生成的扩展加载器
        return extensionLoader;
    }


    /**
     * @param name :要创建的type接口的实现类的名字，这个name要和真实例的全限定名对应
     * @description:获取扩展实例
     * @date: 2025/10/15 13:45
     * @author: 执笔画棠
     * @return: T
     **/
    public T getExtension(String name) throws Exception {
        //判断传入的name是否为空
        if (StringUtil.isBlank(name)) {
            throw new IllegalArgumentException("extension name should not be null or empty");
        }
        //从缓存中根据name获取对象实例，如果没有就创建一个新的holder存实例
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            //这里只是创建了holder容器，容器内的obj实例还是要在后面创建
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }

        //创建实例对象并缓存到holder中
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                //双重检测，防止其他线程在间隙创建了实例
                instance = holder.get();
                if (instance == null) {
                    //调用创建单例对象方法
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }


    /**
     * @param name :要创建的实例的name，从配置文件中读取的
     * @description:创建实例对象
     * @date: 2025/10/15 14:01
     * @author: 执笔画棠
     * @return: java.lang.Object
     **/
    private Object createExtension(String name) throws Exception {

        /*
        getExtensionClasses这个方法的作用就出来了，和前面的cachedClasses对应上了
        在这个方法内部，会先查一下缓存中有没有name和具体实现类全限定名对应的缓存
        如果没有再去从文件夹中加载。这里getExtensionClasses()得到的就是全部的map集合
        所有的name和其对应关系都在，再调用map集合的get方法根据name查即可
         */
        Class<?> clazz = getExtensionClasses().get(name);
        log.info("准备创建扩展实例: {}", clazz.getName());
        if (clazz == null) {
            throw new RuntimeException("扩展类不存在" + name);
        }

        // 2. SingletonFactory.getInstance(clazz) 是否可能返回 null？
        // (我们已经知道，在旧代码下是可能的，在新代码下会抛异常)


//        Object instance = SingletonFactory.getInstanceOld(clazz);
        Object instance = SingletonFactory.getInstance(clazz);
        log.info("SingletonFactory返回的实例: {}", instance);
        log.info("实例是否为null: {}", instance == null);
        // 3. 防御性检查
        if (instance == null) {
            log.info("扩展类实例为空" + clazz);
            throw new IllegalStateException("SingletonFactory返回了null实例,类名: " + clazz.getName());
        }
        //获取实例，使用单例工厂SingletonFactory创建实例对象
        //到这里就串起来了，clazz就是name对应的具体的实现类的全限定名
        //进到单例工厂里面看是一样的，getinstance方法需要的就是
        //具体实现类的全限定名
        return (T) instance;
//        return (T) SingletonFactory.getInstance(clazz);
    }

    private Map<String, Class<?>> getExtensionClasses() {

        //从缓存中获取所有的类,cachedClassess存的是一个map集合，这里是获取了
        //全部的map
        Map<String, Class<?>> classes = cachedClasses.get();
        //如果缓存中没有，进行双重检测并从文件夹中加载
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = new HashMap<>();
                    //重点来了，从文件夹中加载所有的扩展类
                    loadDirectory(classes);
                    cachedClasses.set(classes);
                }
            }
        }

        return classes;
    }

    /*java的spi机制的体现*/
    private void loadDirectory(Map<String, Class<?>> extensionClasses) {
        //构建配置文件的路径，SERVICE_DIRECTORY一开始定义的这个起作用，type.getName() 会返回这个接口的完全限定名
        String fileName = ExtensionLoader.SERVICE_DIRECTORY + type.getName();

        try {
            //从类加载器中获取资源文件的URL，然后从中读取配置文件内容
            Enumeration<URL> urls;
            //依然是获取加载自定义扩展加载器的加载器，至于为什么要拿到这个加载器
            //是因为通常使用当前类的类加载器来加载资源文件，可以确保在多种部署环境下都能正确找到资源。
            ClassLoader classLoader = ExtensionLoader.class.getClassLoader();
            //这个会在整个classpath中搜索所有名为filename的文件，返回一个包含他们url的枚举集合类
            //也就意味着可以在不同的jar包中都定义一个同路径同名的配置文件，这个方法可以把他们全找出来
            urls = classLoader.getResources(fileName);
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    //这里循环处理，找出所有配置文件，nextelement方法是找是否有下一个配置文件
                    URL resourceUrl = urls.nextElement();
                    //加载并解析，第一个参数就代表缓存映射表的那个map集合，后面是类加载器和配置文件的url
                    //解析每一个配置文件，解析其每一行
                    loadResource(extensionClasses, classLoader, resourceUrl);
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    /**
     * @param extensionClasses :用于缓存的map集合
     * @param classLoader      :类加载器
     * @param resourceUrl      :配置文件的url
     * @description:
     * @date: 2025/10/15 15:01
     * @author: 执笔画棠
     * @return: void
     **/
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, URL resourceUrl) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceUrl.openStream(), UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                //过滤掉注释
                final int ci = line.indexOf('#');
                if (ci > 0) {
                    line = line.substring(0, ci);
                }
                //去掉空格
                line = line.trim();
                if (line.length() > 0) {
                    String clazzName = null;
                    try {
                        //实现类的key value对解析，存入到map中
                        final int ei = line.indexOf('=');
                        String name = line.substring(0, ei).trim();
                        clazzName = line.substring(ei + 1).trim();
                        if (name.length() > 0 && clazzName.length() > 0) {
                            Class<?> clazz = classLoader.loadClass(clazzName);
                            extensionClasses.put(name, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        log.error(e.getMessage());
                        // 将原始异常包装成一个非受检异常，并向上抛出，中断整个加载过程！
                        throw new IllegalStateException("Failed to load extension class [" + clazzName + "] from config file [" + resourceUrl + "]", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }


}
