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

package org.springframework.cloud.netflix.eureka.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.EurekaJacksonCodec;
import com.netflix.discovery.converters.wrappers.CodecWrapper;
import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.eureka.DefaultEurekaServerContext;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.JerseyReplicationClient;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.actuator.HasFeatures;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.netflix.eureka.EurekaConstants;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author Gunnar Hillert
 * @author Biju Kunjummen
 * @author Fahim Farook
 */
@Configuration(proxyBeanMethods = false)
@Import(EurekaServerInitializerConfiguration.class) // 导入EurekaServerInitializerConfiguration类，该类实现了SmartLifecycle，基于lifecycle启动Eureka Server并发布事件
@ConditionalOnBean(EurekaServerMarkerConfiguration.Marker.class) // 表示只有在Spring容器中含有Market实例时，才会加载当前Bean，而Market是在@EnableEurekaServer注解作用下初始化的
@EnableConfigurationProperties({ EurekaDashboardProperties.class,
		InstanceRegistryProperties.class })
@PropertySource("classpath:/eureka/server.properties") // 加载配置文件
public class EurekaServerAutoConfiguration implements WebMvcConfigurer { // Eureka Server端的Spring Boot自动装配类

	/**
	 * List of packages containing Jersey resources required by the Eureka server.
	 */
	private static final String[] EUREKA_PACKAGES = new String[] {
			"com.netflix.discovery", "com.netflix.eureka" };

	@Autowired
	private ApplicationInfoManager applicationInfoManager; // 注入ApplicationInfoManager

	@Autowired
	private EurekaServerConfig eurekaServerConfig;

	@Autowired
	private EurekaClientConfig eurekaClientConfig; // 注入EurekaClientConfig

	@Autowired
	private EurekaClient eurekaClient; // 注入EurekaClient

	@Autowired
	private InstanceRegistryProperties instanceRegistryProperties;

	/**
	 * A {@link CloudJacksonJson} instance.
	 */
	public static final CloudJacksonJson JACKSON_JSON = new CloudJacksonJson();

	@Bean
	public HasFeatures eurekaServerFeature() {
		return HasFeatures.namedFeature("Eureka Server",
				EurekaServerAutoConfiguration.class);
	}

	@Bean
	@ConditionalOnProperty(prefix = "eureka.dashboard", name = "enabled",
			matchIfMissing = true)
	public EurekaController eurekaController() { // 初始化EurekaController，提供一些额外的接口用来获取Eureka Server的信息
		return new EurekaController(this.applicationInfoManager);
	}

	static {
		CodecWrappers.registerWrapper(JACKSON_JSON);
		EurekaJacksonCodec.setInstance(JACKSON_JSON.getCodec());
	}

	@Bean
	public ServerCodecs serverCodecs() {
		return new CloudServerCodecs(this.eurekaServerConfig);
	}

	private static CodecWrapper getFullJson(EurekaServerConfig serverConfig) {
		CodecWrapper codec = CodecWrappers.getCodec(serverConfig.getJsonCodecName());
		return codec == null ? CodecWrappers.getCodec(JACKSON_JSON.codecName()) : codec;
	}

	private static CodecWrapper getFullXml(EurekaServerConfig serverConfig) {
		CodecWrapper codec = CodecWrappers.getCodec(serverConfig.getXmlCodecName());
		return codec == null ? CodecWrappers.getCodec(CodecWrappers.XStreamXml.class)
				: codec;
	}

	@Bean
	@ConditionalOnMissingBean
	public ReplicationClientAdditionalFilters replicationClientAdditionalFilters() {
		return new ReplicationClientAdditionalFilters(Collections.emptySet());
	}

	@Bean
	public PeerAwareInstanceRegistry peerAwareInstanceRegistry( // 初始化PeerAwareInstanceRegistry，但真实的对象实例是InstanceRegistry，只不过指向了PeerAwareInstanceRegistry
			ServerCodecs serverCodecs) {
		this.eurekaClient.getApplications(); // force initialization
		return new InstanceRegistry(this.eurekaServerConfig, this.eurekaClientConfig, // 创建InstanceRegistry
				serverCodecs, this.eurekaClient,
				this.instanceRegistryProperties.getExpectedNumberOfClientsSendingRenews(),
				this.instanceRegistryProperties.getDefaultOpenForTrafficCount());
	}

	@Bean
	@ConditionalOnMissingBean
	public PeerEurekaNodes peerEurekaNodes(PeerAwareInstanceRegistry registry,
			ServerCodecs serverCodecs,
			ReplicationClientAdditionalFilters replicationClientAdditionalFilters) {
		return new RefreshablePeerEurekaNodes(registry, this.eurekaServerConfig, // 创建PeerEurekaNodes服务节点信息，当有节点注册上来时，通知集群中的其它服务节点
				this.eurekaClientConfig, serverCodecs, this.applicationInfoManager,
				replicationClientAdditionalFilters);
	}

	@Bean
	public EurekaServerContext eurekaServerContext(ServerCodecs serverCodecs,
			PeerAwareInstanceRegistry registry, PeerEurekaNodes peerEurekaNodes) {
		return new DefaultEurekaServerContext(this.eurekaServerConfig, serverCodecs, // 初始化Eureka Server的上下文，其@PostConstruct注解方法中调用PeerEurekaNodes.start()方法和PeerAwareInstanceRegistry.inist()方法
				registry, peerEurekaNodes, this.applicationInfoManager);
	}

	@Bean
	public EurekaServerBootstrap eurekaServerBootstrap(PeerAwareInstanceRegistry registry,
			EurekaServerContext serverContext) {
		return new EurekaServerBootstrap(this.applicationInfoManager, // 初始化EurekaServerBootstrap，等待EurekaServerInitializerConfiguration的start方法调用contextInitialized方法启动Eureka Server
				this.eurekaClientConfig, this.eurekaServerConfig, registry,
				serverContext);
	}

	/**
	 * Register the Jersey filter.
	 * @param eurekaJerseyApp an {@link Application} for the filter to be registered
	 * @return a jersey {@link FilterRegistrationBean}
	 */
	@Bean
	public FilterRegistrationBean<?> jerseyFilterRegistration(
			javax.ws.rs.core.Application eurekaJerseyApp) { // 初始化Eureka拦截器，ServletContainer中实现了jersey框架，通过它来实现Eureka Server对外的restfull接口
		FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<Filter>();
		bean.setFilter(new ServletContainer(eurekaJerseyApp));
		bean.setOrder(Ordered.LOWEST_PRECEDENCE);
		bean.setUrlPatterns(
				Collections.singletonList(EurekaConstants.DEFAULT_PREFIX + "/*")); // 设置拦截路径：/eureka/*

		return bean;
	}

	/**
	 * Construct a Jersey {@link javax.ws.rs.core.Application} with all the resources
	 * required by the Eureka server.
	 * @param environment an {@link Environment} instance to retrieve classpath resources
	 * @param resourceLoader a {@link ResourceLoader} instance to get classloader from
	 * @return created {@link Application} object
	 */
	@Bean
	public javax.ws.rs.core.Application jerseyApplication(Environment environment, // 初始化jersey的Application
			ResourceLoader resourceLoader) {

		ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(
				false, environment);

		// Filter to include only classes that have a particular annotation.
		//
		provider.addIncludeFilter(new AnnotationTypeFilter(Path.class));
		provider.addIncludeFilter(new AnnotationTypeFilter(Provider.class));

		// Find classes in Eureka packages (or subpackages)
		//
		Set<Class<?>> classes = new HashSet<>();
		for (String basePackage : EUREKA_PACKAGES) {
			Set<BeanDefinition> beans = provider.findCandidateComponents(basePackage);
			for (BeanDefinition bd : beans) {
				Class<?> cls = ClassUtils.resolveClassName(bd.getBeanClassName(),
						resourceLoader.getClassLoader());
				classes.add(cls);
			}
		}

		// Construct the Jersey ResourceConfig
		Map<String, Object> propsAndFeatures = new HashMap<>();
		propsAndFeatures.put(
				// Skip static content used by the webapp
				ServletContainer.PROPERTY_WEB_PAGE_CONTENT_REGEX,
				EurekaConstants.DEFAULT_PREFIX + "/(fonts|images|css|js)/.*");

		DefaultResourceConfig rc = new DefaultResourceConfig(classes);
		rc.setPropertiesAndFeatures(propsAndFeatures);

		return rc;
	}

	@Bean
	@ConditionalOnBean(name = "httpTraceFilter")
	public FilterRegistrationBean<?> traceFilterRegistration(
			@Qualifier("httpTraceFilter") Filter filter) {
		FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<Filter>();
		bean.setFilter(filter);
		bean.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
		return bean;
	}

	@Configuration(proxyBeanMethods = false)
	protected static class EurekaServerConfigBeanConfiguration { // EurekaServerConfig配置类

		@Bean
		@ConditionalOnMissingBean
		public EurekaServerConfig eurekaServerConfig(EurekaClientConfig clientConfig) { // 先注入EurekaClientConfig
			EurekaServerConfigBean server = new EurekaServerConfigBean(); // 初始化EurekaServerConfig
			if (clientConfig.shouldRegisterWithEureka()) { // 允许注册到Eureka时
				// Set a sensible default if we are supposed to replicate
				server.setRegistrySyncRetries(5); // 设置服务同步最大重试次数，默认5次
			}
			return server;
		}

	}

	/**
	 * {@link PeerEurekaNodes} which updates peers when /refresh is invoked. Peers are
	 * updated only if <code>eureka.client.use-dns-for-fetching-service-urls</code> is
	 * <code>false</code> and one of following properties have changed.
	 * <p>
	 * </p>
	 * <ul>
	 * <li><code>eureka.client.availability-zones</code></li>
	 * <li><code>eureka.client.region</code></li>
	 * <li><code>eureka.client.service-url.&lt;zone&gt;</code></li>
	 * </ul>
	 */
	static class RefreshablePeerEurekaNodes extends PeerEurekaNodes
			implements ApplicationListener<EnvironmentChangeEvent> { // Eureka Server集群节点列表

		private ReplicationClientAdditionalFilters replicationClientAdditionalFilters;

		RefreshablePeerEurekaNodes(final PeerAwareInstanceRegistry registry, // 实例化RefreshablePeerEurekaNodes
				final EurekaServerConfig serverConfig,
				final EurekaClientConfig clientConfig, final ServerCodecs serverCodecs,
				final ApplicationInfoManager applicationInfoManager,
				final ReplicationClientAdditionalFilters replicationClientAdditionalFilters) {
			super(registry, serverConfig, clientConfig, serverCodecs, // 调用父类PeerEurekaNodes构造函数
					applicationInfoManager);
			this.replicationClientAdditionalFilters = replicationClientAdditionalFilters;
		}

		@Override
		protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
			JerseyReplicationClient replicationClient = JerseyReplicationClient
					.createReplicationClient(serverConfig, serverCodecs, // 创建JerseyReplicationClient
							peerEurekaNodeUrl);

			this.replicationClientAdditionalFilters.getFilters()
					.forEach(replicationClient::addReplicationClientFilter);

			String targetHost = hostFromUrl(peerEurekaNodeUrl);
			if (targetHost == null) {
				targetHost = "host";
			}
			return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl,
					replicationClient, serverConfig);
		}

		@Override
		public void onApplicationEvent(final EnvironmentChangeEvent event) {
			if (shouldUpdate(event.getKeys())) {
				updatePeerEurekaNodes(resolvePeerUrls());
			}
		}

		/*
		 * Check whether specific properties have changed.
		 */
		protected boolean shouldUpdate(final Set<String> changedKeys) {
			assert changedKeys != null;

			// if eureka.client.use-dns-for-fetching-service-urls is true, then
			// service-url will not be fetched from environment.
			if (this.clientConfig.shouldUseDnsForFetchingServiceUrls()) {
				return false;
			}

			if (changedKeys.contains("eureka.client.region")) {
				return true;
			}

			for (final String key : changedKeys) {
				// property keys are not expected to be null.
				if (key.startsWith("eureka.client.service-url.")
						|| key.startsWith("eureka.client.availability-zones.")) {
					return true;
				}
			}
			return false;
		}

	}

	class CloudServerCodecs extends DefaultServerCodecs {

		CloudServerCodecs(EurekaServerConfig serverConfig) {
			super(getFullJson(serverConfig),
					CodecWrappers.getCodec(CodecWrappers.JacksonJsonMini.class),
					getFullXml(serverConfig),
					CodecWrappers.getCodec(CodecWrappers.JacksonXmlMini.class));
		}

	}

}
