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

package org.springframework.cloud.sleuth.zipkin2.sender;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for Zipkin sender.
 *
 * @author Marcin Grzejszczak
 * @since 1.3.1
 */
@ConfigurationProperties("spring.zipkin.sender")
public class ZipkinSenderProperties {// Sleuth整合Zipkin上报的配置

	/**
	 * Means of sending spans to Zipkin.
	 */
	private SenderType type; // 上报类型

	public SenderType getType() {
		return this.type;
	}

	public void setType(SenderType type) {
		this.type = type;
	}

	/**
	 * Types of a sender.
	 */
	public enum SenderType {

		/**
		 * ActiveMQ sender.
		 */
		ACTIVEMQ,

		/**
		 * RabbitMQ sender.
		 */
		RABBIT,

		/**
		 * Kafka sender.
		 */
		KAFKA,

		/**
		 * HTTP based sender.
		 */
		WEB

	}

}
