/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.eureka.serviceregistry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;

/**
 * @author Dave Syer
 * @author Spencer Gibb
 * @author Jon Schneider
 * @author Jakub Narloch
 * @author Raiyan Raiyan
 */
public class EurekaAutoServiceRegistration implements AutoServiceRegistration, // 基于Eureka实现的服务自动注册类（Eureka采用Lifecycle进行服务注册、Nacos采用cloud类AbstractAutoServiceRegistration基于WebServerInitializedEvent事件进行服务注册）
		SmartLifecycle, Ordered, SmartApplicationListener { // 在EurekaClientAutoConfiguration进行初始化，实现Lifecycle接口并基于lifecycle进行处理执行

	private static final Log log = LogFactory.getLog(EurekaAutoServiceRegistration.class);

	private AtomicBoolean running = new AtomicBoolean(false);

	private int order = 0; // EurekaServerInitializerConfiguration的order为1（客户端先注册，服务端后启动）

	private AtomicInteger port = new AtomicInteger(0);

	private ApplicationContext context;

	private EurekaServiceRegistry serviceRegistry; // 注入基于Eureka实现的服务注册类

	private EurekaRegistration registration; // 注入基于Eureka实现的服务注册类的标记扩展类

	public EurekaAutoServiceRegistration(ApplicationContext context, // 在EurekaClientAutoConfiguration进行初始化
			EurekaServiceRegistry serviceRegistry, EurekaRegistration registration) {
		this.context = context;
		this.serviceRegistry = serviceRegistry;
		this.registration = registration;
	}

	@Override
	public void start() { // Lifecycle接口的start方法，Spring容器初始化完成后调用，是Eureka服务注册的入口
		// only set the port if the nonSecurePort or securePort is 0 and this.port != 0
		if (this.port.get() != 0) {
			if (this.registration.getNonSecurePort() == 0) {
				this.registration.setNonSecurePort(this.port.get());
			}

			if (this.registration.getSecurePort() == 0 && this.registration.isSecure()) {
				this.registration.setSecurePort(this.port.get());
			}
		}

		// only initialize if nonSecurePort is greater than 0 and it isn't already running
		// because of containerPortInitializer below
		if (!this.running.get() && this.registration.getNonSecurePort() > 0) {

			this.serviceRegistry.register(this.registration); // 委托给ServiceRegistry发起服务注册

			this.context.publishEvent(new InstanceRegisteredEvent<>(this, // 发布服务实例注册事件InstanceRegisteredEvent
					this.registration.getInstanceConfig()));
			this.running.set(true);
		}
	}

	@Override
	public void stop() {
		this.serviceRegistry.deregister(this.registration);
		this.running.set(false);
	}

	@Override
	public boolean isRunning() {
		return this.running.get();
	}

	@Override
	public int getPhase() {
		return 0;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		stop();
		callback.run();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return WebServerInitializedEvent.class.isAssignableFrom(eventType)
				|| ContextClosedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof WebServerInitializedEvent) {
			onApplicationEvent((WebServerInitializedEvent) event);
		}
		else if (event instanceof ContextClosedEvent) {
			onApplicationEvent((ContextClosedEvent) event);
		}
	}

	public void onApplicationEvent(WebServerInitializedEvent event) {
		// TODO: take SSL into account
		String contextName = event.getApplicationContext().getServerNamespace();
		if (contextName == null || !contextName.equals("management")) {
			int localPort = event.getWebServer().getPort();
			if (this.port.get() == 0) {
				log.info("Updating port to " + localPort);
				this.port.compareAndSet(0, localPort);
				start();
			}
		}
	}

	public void onApplicationEvent(ContextClosedEvent event) {
		if (event.getApplicationContext() == context) {
			stop();
		}
	}

}
