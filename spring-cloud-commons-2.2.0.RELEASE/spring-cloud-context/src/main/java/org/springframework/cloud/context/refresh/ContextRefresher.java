/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.cloud.context.refresh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.Banner.Mode;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.cloud.bootstrap.BootstrapApplicationListener;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.CompositePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.context.support.StandardServletEnvironment;

/**
 * @author Dave Syer
 * @author Venil Noronha
 */
public class ContextRefresher {

	private static final String REFRESH_ARGS_PROPERTY_SOURCE = "refreshArgs";

	private static final String[] DEFAULT_PROPERTY_SOURCES = new String[] {
			// order matters, if cli args aren't first, things get messy
			CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME,
			"defaultProperties" };

	private Set<String> standardSources = new HashSet<>(
			Arrays.asList(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
					StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.JNDI_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONFIG_PROPERTY_SOURCE_NAME,
					StandardServletEnvironment.SERVLET_CONTEXT_PROPERTY_SOURCE_NAME,
					"configurationProperties"));

	private ConfigurableApplicationContext context;

	private RefreshScope scope;

	public ContextRefresher(ConfigurableApplicationContext context, RefreshScope scope) {
		this.context = context;
		this.scope = scope;
	}

	protected ConfigurableApplicationContext getContext() {
		return this.context;
	}

	protected RefreshScope getScope() {
		return this.scope;
	}

	public synchronized Set<String> refresh() { // 刷新Environment
		Set<String> keys = refreshEnvironment(); // 刷新Environment
		this.scope.refreshAll(); // 调用RefreshScope#refreshAll方法，清除ScopeCache的存储容器
		return keys;
	}

	public synchronized Set<String> refreshEnvironment() { // 刷新Environment
		Map<String, Object> before = extract(
				this.context.getEnvironment().getPropertySources());
		addConfigFilesToEnvironment(); // 将最新配置加入到环境变量中
		Set<String> keys = changes(before, // 获取变化的属性集合
				extract(this.context.getEnvironment().getPropertySources())).keySet();
		this.context.publishEvent(new EnvironmentChangeEvent(this.context, keys)); // 发布EnvironmentChangeEvent事件
		return keys;
	}

	/* For testing. */ ConfigurableApplicationContext addConfigFilesToEnvironment() { // 将最新配置加入到环境变量中
		ConfigurableApplicationContext capture = null; // 生成ApplicationContext
		try {
			StandardEnvironment environment = copyEnvironment(
					this.context.getEnvironment());
			SpringApplicationBuilder builder = new SpringApplicationBuilder(Empty.class) // 构建SpringApplicationBuilder
					.bannerMode(Mode.OFF).web(WebApplicationType.NONE)
					.environment(environment);
			// Just the listeners that affect the environment (e.g. excluding logging
			// listener because it has side effects)
			builder.application()
					.setListeners(Arrays.asList(new BootstrapApplicationListener(),
							new ConfigFileApplicationListener()));
			capture = builder.run(); // 运行SpringApplication
			if (environment.getPropertySources().contains(REFRESH_ARGS_PROPERTY_SOURCE)) {
				environment.getPropertySources().remove(REFRESH_ARGS_PROPERTY_SOURCE);
			}
			MutablePropertySources target = this.context.getEnvironment() // 老数据源
					.getPropertySources();
			String targetName = null;
			for (PropertySource<?> source : environment.getPropertySources()) { // 遍历最新的数据源
				String name = source.getName();
				if (target.contains(name)) {
					targetName = name;
				}
				if (!this.standardSources.contains(name)) { // 如果为允许更新的数据源
					if (target.contains(name)) { // 老数据源存在时，进行更新
						target.replace(name, source);
					}
					else { //  // 老数据源不存在时，进行添加
						if (targetName != null) {
							target.addAfter(targetName, source);
						}
						else {
							// targetName was null so we are at the start of the list
							target.addFirst(source);
							targetName = name;
						}
					}
				}
			}
		}
		finally {
			ConfigurableApplicationContext closeable = capture;
			while (closeable != null) {
				try {
					closeable.close(); // 将生成的ApplicationContext关闭掉
				}
				catch (Exception e) {
					// Ignore;
				}
				if (closeable.getParent() instanceof ConfigurableApplicationContext) {
					closeable = (ConfigurableApplicationContext) closeable.getParent();
				}
				else {
					break;
				}
			}
		}
		return capture;
	}

	// Don't use ConfigurableEnvironment.merge() in case there are clashes with property
	// source names
	private StandardEnvironment copyEnvironment(ConfigurableEnvironment input) {
		StandardEnvironment environment = new StandardEnvironment(); // 创建新的StandardEnvironment
		MutablePropertySources capturedPropertySources = environment.getPropertySources(); // 新的数据源
		// Only copy the default property source(s) and the profiles over from the main
		// environment (everything else should be pristine, just like it was on startup).
		for (String name : DEFAULT_PROPERTY_SOURCES) {
			if (input.getPropertySources().contains(name)) {
				if (capturedPropertySources.contains(name)) {
					capturedPropertySources.replace(name,
							input.getPropertySources().get(name));
				}
				else {
					capturedPropertySources.addLast(input.getPropertySources().get(name));
				}
			}
		}
		environment.setActiveProfiles(input.getActiveProfiles());
		environment.setDefaultProfiles(input.getDefaultProfiles());
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("spring.jmx.enabled", false);
		map.put("spring.main.sources", "");
		capturedPropertySources
				.addFirst(new MapPropertySource(REFRESH_ARGS_PROPERTY_SOURCE, map));
		return environment;
	}

	private Map<String, Object> changes(Map<String, Object> before,
			Map<String, Object> after) {
		Map<String, Object> result = new HashMap<String, Object>();
		for (String key : before.keySet()) {
			if (!after.containsKey(key)) {
				result.put(key, null);
			}
			else if (!equal(before.get(key), after.get(key))) {
				result.put(key, after.get(key));
			}
		}
		for (String key : after.keySet()) {
			if (!before.containsKey(key)) {
				result.put(key, after.get(key));
			}
		}
		return result;
	}

	private boolean equal(Object one, Object two) {
		if (one == null && two == null) {
			return true;
		}
		if (one == null || two == null) {
			return false;
		}
		return one.equals(two);
	}

	private Map<String, Object> extract(MutablePropertySources propertySources) {
		Map<String, Object> result = new HashMap<String, Object>();
		List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
		for (PropertySource<?> source : propertySources) {
			sources.add(0, source);
		}
		for (PropertySource<?> source : sources) {
			if (!this.standardSources.contains(source.getName())) {
				extract(source, result);
			}
		}
		return result;
	}

	private void extract(PropertySource<?> parent, Map<String, Object> result) {
		if (parent instanceof CompositePropertySource) {
			try {
				List<PropertySource<?>> sources = new ArrayList<PropertySource<?>>();
				for (PropertySource<?> source : ((CompositePropertySource) parent)
						.getPropertySources()) {
					sources.add(0, source);
				}
				for (PropertySource<?> source : sources) {
					extract(source, result);
				}
			}
			catch (Exception e) {
				return;
			}
		}
		else if (parent instanceof EnumerablePropertySource) {
			for (String key : ((EnumerablePropertySource<?>) parent).getPropertyNames()) {
				result.put(key, parent.getProperty(key));
			}
		}
	}

	@Configuration(proxyBeanMethods = false)
	protected static class Empty {

	}

}
