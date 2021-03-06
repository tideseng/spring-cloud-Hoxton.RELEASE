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

package org.springframework.cloud.gateway.handler.predicate;

import org.springframework.cloud.gateway.support.AbstractConfigurable;

public abstract class AbstractRoutePredicateFactory<C> extends AbstractConfigurable<C> // 路由断言工厂抽象类（所有的路由断言规则都需要继承该类）
		implements RoutePredicateFactory<C> {

	public AbstractRoutePredicateFactory(Class<C> configClass) {
		super(configClass);
	}

}
