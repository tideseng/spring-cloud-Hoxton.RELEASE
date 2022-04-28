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

package org.springframework.cloud.sleuth.instrument.web.client;

import org.assertj.core.api.BDDAssertions;
import org.junit.Test;
import org.mockito.BDDMockito;

import org.springframework.web.reactive.function.client.ClientResponse;

public class TraceExchangeFilterFunctionHttpAdapterTests {

	@Test
	public void should_return_0_when_invalid_status_code_is_returned() {
		ClientResponse clientResponse = BDDMockito.mock(ClientResponse.class);
		BDDMockito.given(clientResponse.rawStatusCode())
				.willThrow(new IllegalStateException("Boom"));
		TraceExchangeFilterFunction.HttpAdapter adapter = new TraceExchangeFilterFunction.HttpAdapter();

		Integer statusCode = adapter.statusCodeAsInt(clientResponse);

		BDDAssertions.then(statusCode).isZero();
	}

	@Test
	public void should_return_status_code_when_valid_status_code_is_returned() {
		ClientResponse clientResponse = BDDMockito.mock(ClientResponse.class);
		BDDMockito.given(clientResponse.rawStatusCode()).willReturn(200);
		TraceExchangeFilterFunction.HttpAdapter adapter = new TraceExchangeFilterFunction.HttpAdapter();

		Integer statusCode = adapter.statusCodeAsInt(clientResponse);

		BDDAssertions.then(statusCode).isEqualTo(200);
	}

}
