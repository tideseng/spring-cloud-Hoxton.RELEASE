/*
 * Copyright 2019-2019 the original author or authors.
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

package reactor.blockhound.integration;

import reactor.blockhound.BlockHound;

/**
 * @author Tim Ysewyn
 */
public class CustomBlockHoundIntegration implements BlockHoundIntegration {

	@Override
	public void applyTo(BlockHound.Builder builder) {
		// Uses
		// ch.qos.logback.classic.spi.PackagingDataCalculator#getImplementationVersion
		builder.allowBlockingCallsInside(
				"org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler",
				"logError");
		builder.allowBlockingCallsInside("reactor.util.Loggers$Slf4JLogger", "debug");

		// Uses org.springframework.util.JdkIdGenerator#generateId
		// Uses UUID#randomUUID
		builder.allowBlockingCallsInside(
				"org.springframework.web.server.session.InMemoryWebSessionStore",
				"lambda$createWebSession$0");

		// Uses java.util.Random#nextInt
		builder.allowBlockingCallsInside("org.springframework.util.MimeTypeUtils",
				"generateMultipartBoundary");

		// SECURITY RELATED

		// For HTTPS traffic
		builder.allowBlockingCallsInside("io.netty.handler.ssl.SslHandler",
				"channelActive");
		builder.allowBlockingCallsInside("io.netty.handler.ssl.SslHandler", "unwrap");
		builder.allowBlockingCallsInside("io.netty.handler.ssl.SslContext",
				"newClientContextInternal");

		// Uses
		// org.springframework.cloud.commons.httpclient.DefaultApacheHttpClientConnectionManagerFactory#newConnectionManager
		// Uses javax.net.ssl.SSLContext#init
		builder.allowBlockingCallsInside(
				"org.springframework.cloud.netflix.ribbon.SpringClientFactory",
				"getContext");

		// Uses org.springframework.security.crypto.bcrypt.BCrypt#gensalt
		// Uses java.security.SecureRandom#nextBytes
		builder.allowBlockingCallsInside(
				"org.springframework.security.authentication.AbstractUserDetailsReactiveAuthenticationManager",
				"lambda$authenticate$4");
	}

}
