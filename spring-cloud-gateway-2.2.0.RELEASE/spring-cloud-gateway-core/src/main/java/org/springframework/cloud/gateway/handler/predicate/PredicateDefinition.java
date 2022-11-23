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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.validation.ValidationException;
import javax.validation.constraints.NotNull;

import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.validation.annotation.Validated;

import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * @author Spencer Gibb
 */
@Validated
public class PredicateDefinition { // 路由断言规则（判断路由是否成立）

	@NotNull
	private String name; // 断言工厂名称（命令规则见NameUtils.normalizeRoutePredicateName）

	private Map<String, String> args = new LinkedHashMap<>(); // 断言工厂属性容器

	public PredicateDefinition() {
	}

	public PredicateDefinition(String text) { // 创建PredicateDefinition（text值由ObjectToObjectConverter转换器传入，如：Path=/xx/**）
		int eqIdx = text.indexOf('=');
		if (eqIdx <= 0) {
			throw new ValidationException("Unable to parse PredicateDefinition text '"
					+ text + "'" + ", must be of the form name=value");
		}
		setName(text.substring(0, eqIdx)); // 设置name属性值，属性值为=前面的部分

		String[] args = tokenizeToStringArray(text.substring(eqIdx + 1), ","); // =后面的部分以逗号分隔的数组为属性值

		for (int i = 0; i < args.length; i++) {
			this.args.put(NameUtils.generateName(i), args[i]); // 给断言工厂生成属性名，并将属性值设置到args变量中
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Map<String, String> getArgs() {
		return args;
	}

	public void setArgs(Map<String, String> args) {
		this.args = args;
	}

	public void addArg(String key, String value) {
		this.args.put(key, value);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		PredicateDefinition that = (PredicateDefinition) o;
		return Objects.equals(name, that.name) && Objects.equals(args, that.args);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, args);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("PredicateDefinition{");
		sb.append("name='").append(name).append('\'');
		sb.append(", args=").append(args);
		sb.append('}');
		return sb.toString();
	}

}
