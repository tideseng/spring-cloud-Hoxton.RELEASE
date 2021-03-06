/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.netflix.ribbon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.ServerListFilter;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Declarative configuration for a ribbon client. Add this annotation to any
 * <code>@Configuration</code> and then inject a {@link SpringClientFactory} to access the
 * client that is created.
 *
 * @author Dave Syer
 */
@Configuration(proxyBeanMethods = false)
@Import(RibbonClientConfigurationRegistrar.class) // 导入RibbonClientConfigurationRegistrar类进行自动注册Bean
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RibbonClient { // 代码配置RibbonClient相关信息（除此之外还有属性配置方式）

	/**
	 * Synonym for name (the name of the client).
	 *
	 * @see #name()
	 * @return name of the Ribbon client
	 */
	String value() default ""; // 同name属性

	/**
	 * The name of the ribbon client, uniquely identifying a set of client resources,
	 * including a load balancer.
	 * @return name of the Ribbon client
	 */
	String name() default ""; // Ribbon客户端名称/服务名

	/**
	 * A custom <code>@Configuration</code> for the ribbon client. Can contain override
	 * <code>@Bean</code> definition for the pieces that make up the client, for instance
	 * {@link ILoadBalancer}, {@link ServerListFilter}, {@link IRule}.
	 *
	 * @see RibbonClientConfiguration for the defaults
	 * @return the custom Ribbon client configuration
	 */
	Class<?>[] configuration() default {}; // Ribbon客户端自定义配置文件

}
