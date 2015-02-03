/*
 * Copyright (C) 2015 Mr.Simple <bboyfeiyu@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.simple.eventbus;

import android.util.Log;

import org.simple.eventbus.handler.AsyncEventHandler;
import org.simple.eventbus.handler.DefaultEventHandler;
import org.simple.eventbus.handler.EventHandler;
import org.simple.eventbus.handler.UIThreadEventHandler;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 启动一个后台线程来发布消息,在死循环中发布消息,默认将接收方法执行在UI线程,如果需要接收方法执行在异步线程,那么则直接使用再开启一个执行线程。
 * 使用享元模式复用Event对象，类似于Handler中的Message.
 * 
 * @author mrsimple
 */
public final class EventBus {

    /**
     * default descriptor
     */
    private static final String DESCRIPTOR = EventBus.class.getSimpleName();

    /**
     * 事件总线描述符描述符
     */
    private String mDesc = DESCRIPTOR;

    /**
     * EventType-Subcriptions map
     */
    private final Map<EventType, CopyOnWriteArrayList<Subscription>> mSubcriberMap = new HashMap<EventType, CopyOnWriteArrayList<Subscription>>();

    /**
     * the thread local event queue, every single thread has it's own queue.
     */
    ThreadLocal<Queue<EventType>> mLocalEvents = new ThreadLocal<Queue<EventType>>() {
        protected java.util.Queue<EventType> initialValue() {
            return new ConcurrentLinkedQueue<EventType>();
        };
    };

    /**
     * the event dispatcher
     */
    EventDispatcher mDispatcher = new EventDispatcher();

    /**
     * The Default EventBus instance
     */
    private static EventBus sDefaultBus;

    /**
     * private Constructor
     */
    private EventBus() {
        this(DESCRIPTOR);
    }

    /**
     * constructor with desc
     * 
     * @param desc the descriptor of eventbus
     */
    public EventBus(String desc) {
        mDesc = desc;
    }

    /**
     * @return
     */
    public static EventBus getDefault() {
        if (sDefaultBus == null) {
            synchronized (EventBus.class) {
                if (sDefaultBus == null) {
                    sDefaultBus = new EventBus();
                }
            }
        }
        return sDefaultBus;
    }

    /**
     * register a subscriber into the mSubcriberMap, the key is subscriber's
     * method's name and tag which annotated with {@see Subcriber}, the value is
     * a list of Subscription.
     * 
     * @param subscriber the target subscriber
     */
    public void register(Object subscriber) {
        if (subscriber == null) {
            return;
        }
        final Method[] allMethods = subscriber.getClass().getDeclaredMethods();
        for (int i = 0; i < allMethods.length; i++) {
            Method method = allMethods[i];
            // 根据注解来解析函数
            Subcriber annotation = method.getAnnotation(Subcriber.class);
            if (annotation != null) {
                // 获取方法参数
                Class<?>[] paramsTypeClass = method.getParameterTypes();
                // just only one param
                if (paramsTypeClass != null && paramsTypeClass.length == 1) {
                    EventType event = new EventType(paramsTypeClass[0], annotation.tag());
                    TargetMethod subscribeMethod = new TargetMethod(method,
                            paramsTypeClass[0], annotation.mode());
                    // 订阅事件
                    subscibe(event, subscribeMethod, subscriber);
                }
            }
        } // end for
    }

    /**
     * @param event
     * @param method
     * @param subscriber
     */
    private void subscibe(EventType event, TargetMethod method, Object subscriber) {
        CopyOnWriteArrayList<Subscription> subscriptionLists = mSubcriberMap
                .get(event);
        if (subscriptionLists == null) {
            subscriptionLists = new CopyOnWriteArrayList<Subscription>();
        }

        Subscription newSubscription = new Subscription(subscriber, method);
        if (subscriptionLists.contains(newSubscription)) {
            return;
        }

        subscriptionLists.add(newSubscription);
        // 订阅事件
        mSubcriberMap.put(event, subscriptionLists);
    }

    /**
     * @param subscriber
     */
    public void unregister(Object subscriber) {
        if (subscriber == null) {
            return;
        }

        Iterator<CopyOnWriteArrayList<Subscription>> iterator = mSubcriberMap
                .values().iterator();
        while (iterator.hasNext()) {
            CopyOnWriteArrayList<Subscription> subscriptions = iterator.next();
            if (subscriptions != null) {
                List<Subscription> foundSubscriptions = new LinkedList<Subscription>();
                Iterator<Subscription> subIterator = subscriptions.iterator();
                while (subIterator.hasNext()) {
                    Subscription subscription = subIterator.next();
                    if (subscription.subscriber.equals(subscriber)) {
                        Log.d(getDescriptor(), "### 移除订阅 " + subscriber.getClass().getName());
                        foundSubscriptions.add(subscription);
                    }
                }

                // 移除该subscriber的相关的Subscription
                subscriptions.removeAll(foundSubscriptions);
            }

            // 如果针对某个Event的订阅者数量为空了,那么需要从map中清除
            if (subscriptions == null || subscriptions.size() == 0) {
                iterator.remove();
            }
        }

        Log.d(getDescriptor(), "### 订阅size = " + mSubcriberMap.size());
    }

    /**
     * 发布事件,那么则需要找到对应事件类型的所有订阅者
     * 
     * @param event
     */
    public void post(Object event) {
        post(event, EventType.DEFAULT_TAG);
    }

    /**
     * 发布事件
     * 
     * @param event 要发布的事件
     * @param tag 事件的tag, 类似于BroadcastReceiver的action
     */
    public void post(Object event, String tag) {
        mLocalEvents.get().offer(new EventType(event.getClass(), tag));
        // dispatchEvents(event);
        mDispatcher.dispatchEvents(event);
    }

    /**
     * 获取某个事件的观察者列表
     * 
     * @param event 事件类型
     * @return
     */
    @SuppressWarnings("unchecked")
    public Collection<Subscription> getSubscriptions(EventType event) {
        List<Subscription> result = mSubcriberMap.get(event);
        return result != null ? result : Collections.EMPTY_LIST;
    }

    /**
     * 获取已经注册的事件类型列表
     * 
     * @return
     */
    @SuppressWarnings("unchecked")
    public Collection<EventType> getEventTypes() {
        Collection<EventType> result = mSubcriberMap.keySet();
        return result != null ? result : Collections.EMPTY_LIST;
    }

    /**
     * 获取等待处理的事件队列
     * 
     * @return
     */
    public Queue<EventType> getEventQueue() {
        return mLocalEvents.get();
    }

    /**
     * @return
     */
    public String getDescriptor() {
        return mDesc;
    }

    /**
     * 事件分发器
     * 
     * @author mrsimple
     */
    private class EventDispatcher {

        /**
         * 将接收方法执行在UI线程
         */
        UIThreadEventHandler mUIThreadEventHandler = new UIThreadEventHandler();

        /**
         * 哪个线程执行的post,接收方法就执行在哪个线程
         */
        EventHandler mPostThreadHandler = new DefaultEventHandler();

        /**
         * 异步线程中执行订阅方法
         */
        EventHandler mAsyncEventHandler = new AsyncEventHandler();

        /**
         * @param event
         */
        void dispatchEvents(Object event) {
            Queue<EventType> eventsQueue = mLocalEvents.get();
            while (eventsQueue.size() > 0) {
                handleEvent(eventsQueue.poll(), event);
            }
        }

        private void handleEvent(EventType eventType, Object event) {
            List<Subscription> subscriptions = mSubcriberMap.get(eventType);
            if (subscriptions == null) {
                return;
            }

            for (Subscription subscription : subscriptions) {
                final ThreadMode mode = subscription.threadMode;
                EventHandler eventHandler = getEventHandler(mode);
                // 处理事件
                eventHandler.handleEvent(subscription, event);
            }
        }

        private EventHandler getEventHandler(ThreadMode mode) {
            if (mode == ThreadMode.ASYNC) {
                return mAsyncEventHandler;
            }

            if (mode == ThreadMode.POST) {
                return mPostThreadHandler;
            }

            return mUIThreadEventHandler;
        }
    } // end of EventDispatcher

}
