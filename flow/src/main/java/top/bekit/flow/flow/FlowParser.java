/* 
 * 作者：钟勋 (e-mail:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2016-12-19 01:40 创建
 */
package top.bekit.flow.flow;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import top.bekit.event.bus.EventBusHolder;
import top.bekit.event.publisher.DefaultEventPublisher;
import top.bekit.flow.annotation.flow.*;
import top.bekit.flow.annotation.listener.FlowListener;
import top.bekit.flow.engine.TargetContext;
import top.bekit.flow.flow.FlowExecutor.NodeExecutor;
import top.bekit.flow.flow.FlowExecutor.NodeExecutor.NodeDeciderExecutor;
import top.bekit.flow.flow.FlowExecutor.TargetMappingExecutor;
import top.bekit.flow.processor.ProcessorExecutor;
import top.bekit.flow.processor.ProcessorHolder;
import top.bekit.flow.transaction.FlowTxHolder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 流程解析器
 */
public class FlowParser {
    // 日志记录器
    private static final Logger logger = LoggerFactory.getLogger(FlowParser.class);

    /**
     * @param flow            流程
     * @param processorHolder 处理器持有器
     * @param flowTxHolder    流程事务持有器
     * @param eventBusHolder  事件总线持有器
     * @return 流程执行器
     */
    public static FlowExecutor parseFlow(Object flow, ProcessorHolder processorHolder, FlowTxHolder flowTxHolder, EventBusHolder eventBusHolder) {
        logger.info("解析流程：{}", ClassUtils.getQualifiedName(flow.getClass()));
        Flow flowAnnotation = flow.getClass().getAnnotation(Flow.class);
        // 获取流程名称
        String flowName = flowAnnotation.name();
        if (StringUtils.isEmpty(flowName)) {
            flowName = ClassUtils.getShortNameAsProperty(flow.getClass());
        }
        // 新建流程执行器
        FlowExecutor flowExecutor = new FlowExecutor(flowName, flowAnnotation.enableFlowTx(), flow, new DefaultEventPublisher(eventBusHolder.getEventBus(FlowListener.class)));
        if (flowAnnotation.enableFlowTx()) {
            flowExecutor.setFlowTxExecutor(flowTxHolder.getRequiredFlowTxExecutor(flowName));
        }
        for (Method method : flow.getClass().getDeclaredMethods()) {
            // 此处得到的@Node是已经经过@AliasFor属性别名进行属性同步后的结果
            Node nodeAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, Node.class);
            if (nodeAnnotation != null) {
                // 解析节点
                NodeExecutor nodeExecutor = parseNode(nodeAnnotation, method, processorHolder);
                // 添加节点
                flowExecutor.addNode(nodeExecutor);
                // 校验是否为开始节点
                if (method.isAnnotationPresent(StartNode.class)) {
                    flowExecutor.setStartNode(nodeExecutor.getNodeName());
                }
                // 校验是否为结束节点
                if (method.isAnnotationPresent(EndNode.class)) {
                    flowExecutor.addEndNode(nodeExecutor.getNodeName());
                }
            } else if (method.isAnnotationPresent(TargetMapping.class)) {
                // 设置目标对象映射执行器
                flowExecutor.setMappingExecutor(parseTargetMapping(method));
            }
        }
        flowExecutor.validate();

        return flowExecutor;
    }

    // 解析节点
    private static NodeExecutor parseNode(Node nodeAnnotation, Method method, ProcessorHolder processorHolder) {
        logger.debug("解析流程节点：node={}，method={}", nodeAnnotation, method);
        // 获取节点名称
        String nodeName = nodeAnnotation.name();
        if (StringUtils.isEmpty(nodeName)) {
            nodeName = method.getName();
        }
        // 获取处理器
        ProcessorExecutor processorExecutor = null;
        if (StringUtils.isNotEmpty(nodeAnnotation.processor())) {
            processorExecutor = processorHolder.getRequiredProcessorExecutor(nodeAnnotation.processor());
        }
        // 新建节点执行器
        NodeExecutor nodeExecutor = new NodeExecutor(nodeName, processorExecutor, nodeAnnotation.autoExecute(), nodeAnnotation.newTx());
        // 设置节点决策器执行器
        nodeExecutor.setNodeDeciderExecutor(parseNodeDecider(method, processorExecutor));
        nodeExecutor.validate();

        return nodeExecutor;
    }

    // 解析节点决策器
    private static NodeDeciderExecutor parseNodeDecider(Method method, ProcessorExecutor processorExecutor) {
        logger.debug("解析节点决策器：{}", method);
        // 校验方法类型
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException("解析节点决策器" + ClassUtils.getQualifiedMethodName(method) + "必须是public类型");
        }
        // 判断+校验入参类型，可以存在的入参类型：()、(TargetContext)、(T)、(T, TargetContext)————T表示能被处理器返回结果赋值的类型
        NodeDeciderExecutor.ParametersType parametersType;
        Class classOfTarget = null;
        Class[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            // 入参类型：()
            parametersType = NodeDeciderExecutor.ParametersType.NONE;
        } else {
            if (method.isAnnotationPresent(EndNode.class)) {
                throw new IllegalArgumentException("结束节点的决策器" + ClassUtils.getQualifiedMethodName(method) + "不能有入参");
            }
            if (parameterTypes.length == 1) {
                if (parameterTypes[0] == TargetContext.class) {
                    // 入参类型：(TargetContext)
                    parametersType = NodeDeciderExecutor.ParametersType.ONLY_TARGET_CONTEXT;
                    // 解析目标对象类型
                    ResolvableType resolvableType = ResolvableType.forMethodParameter(method, 0);
                    classOfTarget = resolvableType.getGeneric(0).resolve(Object.class);
                } else {
                    // 入参类型：(T)
                    if (processorExecutor == null) {
                        throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "不能有非TargetContext入参，因为这个节点没有处理器");
                    }
                    if (!parameterTypes[0].isAssignableFrom(processorExecutor.getReturnType())) {
                        throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的非TargetContext入参类型必须能被其处理器返回类型赋值");
                    }
                    parametersType = NodeDeciderExecutor.ParametersType.ONLY_PROCESS_RESULT;
                }
            } else if (parameterTypes.length == 2) {
                // 入参类型：(T, TargetContext)
                if (processorExecutor == null) {
                    throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "不能有非TargetContext入参，因为这个节点没有处理器");
                }
                if (!parameterTypes[0].isAssignableFrom(processorExecutor.getReturnType())) {
                    throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的非TargetContext入参类型必须能被其处理器返回类型赋值");
                }
                if (parameterTypes[1] != TargetContext.class) {
                    throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的第二个参数类型必须是TargetContext");
                }
                parametersType = NodeDeciderExecutor.ParametersType.PROCESS_RESULT_AND_TARGET_CONTEXT;
                // 解析目标对象类型
                ResolvableType resolvableType = ResolvableType.forMethodParameter(method, 1);
                classOfTarget = resolvableType.getGeneric(0).resolve(Object.class);
            } else {
                throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的入参个数不能超过2个");
            }
        }
        // 校验返回类型
        if (method.isAnnotationPresent(EndNode.class)) {
            if (method.getReturnType() != void.class) {
                throw new IllegalArgumentException("结束节点的节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的返回类型必须是void");
            }
        } else {
            if (method.getReturnType() != String.class) {
                throw new IllegalArgumentException("节点决策器" + ClassUtils.getQualifiedMethodName(method) + "的返回类型必须是String");
            }
        }

        return new NodeDeciderExecutor(method, parametersType, classOfTarget);
    }

    // 解析目标对象映射方法
    private static TargetMappingExecutor parseTargetMapping(Method method) {
        logger.debug("解析目标对象映射方法：{}", method);
        // 校验方法类型
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException("目标对象映射方法" + ClassUtils.getQualifiedMethodName(method) + "必须是public类型");
        }
        // 校验入参
        Class[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 1) {
            throw new IllegalArgumentException("目标对象映射方法" + ClassUtils.getQualifiedMethodName(method) + "必须只能有一个入参");
        }
        // 校验返回参数
        if (method.getReturnType() != String.class) {
            throw new IllegalArgumentException("目标对象映射方法" + ClassUtils.getQualifiedMethodName(method) + "返回参数必须是String类型");
        }

        return new TargetMappingExecutor(method, parameterTypes[0]);
    }
}
