/* 
 * 作者：钟勋 (e-mail:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2016-12-16 01:14 创建
 */
package top.bekit.event.bus;

import org.springframework.beans.factory.annotation.Autowired;
import top.bekit.event.listener.ListenerExecutor;
import top.bekit.event.listener.ListenerHolder;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * 事件总线持有器（会被注册到spring容器中）
 */
public class EventBusHolder {
    @Autowired
    private ListenerHolder listenerHolder;
    // 事件总线Map（key：总线类型）
    private Map<Class, EventBus> eventBusMap = new HashMap<>();

    // 初始化（根据监听器类型创建相应类型的事件总线，spring自动执行）
    @PostConstruct
    public void init() {
        for (Class type : listenerHolder.getTypes()) {
            // 构造事件总线
            EventBus eventBus = new EventBus();
            for (ListenerExecutor listenerExecutor : listenerHolder.getListenerExecutors(type)) {
                eventBus.register(listenerExecutor);
            }
            // 将事件总线放入持有器中
            eventBusMap.put(type, eventBus);
        }
    }

    /**
     * 获取事件总线
     * （如果不存在该类型的事件总线，则新创建一个）
     *
     * @param type 总线类型
     */
    public synchronized EventBus getEventBus(Class type) {
        if (!eventBusMap.containsKey(type)) {
            eventBusMap.put(type, new EventBus());
        }
        return eventBusMap.get(type);
    }
}
