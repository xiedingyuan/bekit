/* 
 * 作者：钟勋 (e-mail:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2016-12-16 01:14 创建
 */
package top.bekit.event.boot;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 事件总线自动配置类
 */
@Configuration
@Import(EventBusConfiguration.class)
public class EventBusAutoConfiguration {
    // 事件总线由EventBusConfiguration进行配置
    // 本配置类的作用就是在spring-boot项目中自动导入EventBusConfiguration
}
