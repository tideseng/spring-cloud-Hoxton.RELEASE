/**
 * Copyright 2012-2019 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import feign.Logger.NoOpLogger;
import feign.ReflectiveFeign.ParseHandlersByName;
import feign.Request.Options;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import static feign.ExceptionPropagationPolicy.NONE;

/**
 * Feign's purpose is to ease development against http apis that feign restfulness. <br>
 * In implementation, Feign is a {@link Feign#newInstance factory} for generating {@link Target
 * targeted} http apis.
 */
public abstract class Feign {

  public static Builder builder() { // 创建Feign.Builder
    return new Builder();
  }

  /**
   * Configuration keys are formatted as unresolved <a href=
   * "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html" >see
   * tags</a>. This method exposes that format, in case you need to create the same value as
   * {@link MethodMetadata#configKey()} for correlation purposes.
   *
   * <p>
   * Here are some sample encodings:
   *
   * <pre>
   * <ul>
   *   <li>{@code Route53}: would match a class {@code route53.Route53}</li>
   *   <li>{@code Route53#list()}: would match a method {@code route53.Route53#list()}</li>
   *   <li>{@code Route53#listAt(Marker)}: would match a method {@code
   * route53.Route53#listAt(Marker)}</li>
   *   <li>{@code Route53#listByNameAndType(String, String)}: would match a method {@code
   * route53.Route53#listAt(String, String)}</li>
   * </ul>
   * </pre>
   *
   * Note that there is no whitespace expected in a key!
   *
   * @param targetType {@link feign.Target#type() type} of the Feign interface.
   * @param method invoked method, present on {@code type} or its super.
   * @see MethodMetadata#configKey()
   */
  public static String configKey(Class targetType, Method method) { // 获取configKey，格式为：简单类名#(入参简单类名, 入参简单类名)
    StringBuilder builder = new StringBuilder();
    builder.append(targetType.getSimpleName());
    builder.append('#').append(method.getName()).append('(');
    for (Type param : method.getGenericParameterTypes()) {
      param = Types.resolve(targetType, targetType, param);
      builder.append(Types.getRawType(param).getSimpleName()).append(',');
    }
    if (method.getParameterTypes().length > 0) {
      builder.deleteCharAt(builder.length() - 1);
    }
    return builder.append(')').toString();
  }

  /**
   * @deprecated use {@link #configKey(Class, Method)} instead.
   */
  @Deprecated
  public static String configKey(Method method) {
    return configKey(method.getDeclaringClass(), method);
  }

  /**
   * Returns a new instance of an HTTP API, defined by annotations in the {@link Feign Contract},
   * for the specified {@code target}. You should cache this result.
   */
  public abstract <T> T newInstance(Target<T> target); // 生成代理对象，需要子类实现

  public static class Builder {

    private final List<RequestInterceptor> requestInterceptors = // 拦截器（生成FeignClient的代理对象时会从子容器中获取并添加）
        new ArrayList<RequestInterceptor>();
    private Logger.Level logLevel = Logger.Level.NONE;
    private Contract contract = new Contract.Default(); // 锲约（生成FeignClient的代理对象时会从子容器中获取并赋值，默认为SpringMvcContract，当使用了服务降级且开启服务降级时为HystrixDelegatingContract）
    private Client client = new Client.Default(null, null); // 负载均衡客户端或第三方客户端（生成FeignClient的代理对象时会从子容器中获取并赋值）
    private Retryer retryer = new Retryer.Default(); // 重试机制（生成FeignClient的代理对象时会从子容器、全局属性配置、实例属性配置中获取并赋值）
    private Logger logger = new NoOpLogger(); // 日志（生成FeignClient的代理对象时会从子容器中获取并赋值）
    private Encoder encoder = new Encoder.Default(); // 编码器（生成FeignClient的代理对象时会从子容器中获取并赋值）
    private Decoder decoder = new Decoder.Default(); // 解码器（生成FeignClient的代理对象时会从子容器中获取并赋值）
    private QueryMapEncoder queryMapEncoder = new QueryMapEncoder.Default();
    private ErrorDecoder errorDecoder = new ErrorDecoder.Default();
    private Options options = new Options(); // 超时设置（生成FeignClient的代理对象时会从子容器、全局属性配置、实例属性配置中获取并赋值）
    private InvocationHandlerFactory invocationHandlerFactory =
        new InvocationHandlerFactory.Default(); // 初始化InvocationHandlerFactory（默认为InvocationHandlerFactory.Default，当使用了服务降级且开启服务降级时为匿名内部类）
    private boolean decode404;
    private boolean closeAfterDecode = true;
    private ExceptionPropagationPolicy propagationPolicy = NONE;

    public Builder logLevel(Logger.Level logLevel) {
      this.logLevel = logLevel;
      return this;
    }

    public Builder contract(Contract contract) {
      this.contract = contract;
      return this;
    }

    public Builder client(Client client) {
      this.client = client;
      return this;
    }

    public Builder retryer(Retryer retryer) {
      this.retryer = retryer;
      return this;
    }

    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    public Builder encoder(Encoder encoder) {
      this.encoder = encoder;
      return this;
    }

    public Builder decoder(Decoder decoder) {
      this.decoder = decoder;
      return this;
    }

    public Builder queryMapEncoder(QueryMapEncoder queryMapEncoder) {
      this.queryMapEncoder = queryMapEncoder;
      return this;
    }

    /**
     * Allows to map the response before passing it to the decoder.
     */
    public Builder mapAndDecode(ResponseMapper mapper, Decoder decoder) {
      this.decoder = new ResponseMappingDecoder(mapper, decoder);
      return this;
    }

    /**
     * This flag indicates that the {@link #decoder(Decoder) decoder} should process responses with
     * 404 status, specifically returning null or empty instead of throwing {@link FeignException}.
     *
     * <p/>
     * All first-party (ex gson) decoders return well-known empty values defined by
     * {@link Util#emptyValueOf}. To customize further, wrap an existing {@link #decoder(Decoder)
     * decoder} or make your own.
     *
     * <p/>
     * This flag only works with 404, as opposed to all or arbitrary status codes. This was an
     * explicit decision: 404 -> empty is safe, common and doesn't complicate redirection, retry or
     * fallback policy. If your server returns a different status for not-found, correct via a
     * custom {@link #client(Client) client}.
     *
     * @since 8.12
     */
    public Builder decode404() {
      this.decode404 = true;
      return this;
    }

    public Builder errorDecoder(ErrorDecoder errorDecoder) {
      this.errorDecoder = errorDecoder;
      return this;
    }

    public Builder options(Options options) {
      this.options = options;
      return this;
    }

    /**
     * Adds a single request interceptor to the builder.
     */
    public Builder requestInterceptor(RequestInterceptor requestInterceptor) {
      this.requestInterceptors.add(requestInterceptor);
      return this;
    }

    /**
     * Sets the full set of request interceptors for the builder, overwriting any previous
     * interceptors.
     */
    public Builder requestInterceptors(Iterable<RequestInterceptor> requestInterceptors) {
      this.requestInterceptors.clear();
      for (RequestInterceptor requestInterceptor : requestInterceptors) {
        this.requestInterceptors.add(requestInterceptor);
      }
      return this;
    }

    /**
     * Allows you to override how reflective dispatch works inside of Feign.
     */
    public Builder invocationHandlerFactory(InvocationHandlerFactory invocationHandlerFactory) { // 设置InvocationHandlerFactory（当Feign使用了服务降级功能时会覆盖默认值，通过匿名内部类进行设置）
      this.invocationHandlerFactory = invocationHandlerFactory;
      return this;
    }

    /**
     * This flag indicates that the response should not be automatically closed upon completion of
     * decoding the message. This should be set if you plan on processing the response into a
     * lazy-evaluated construct, such as a {@link java.util.Iterator}.
     *
     * </p>
     * Feign standard decoders do not have built in support for this flag. If you are using this
     * flag, you MUST also use a custom Decoder, and be sure to close all resources appropriately
     * somewhere in the Decoder (you can use {@link Util#ensureClosed} for convenience).
     *
     * @since 9.6
     *
     */
    public Builder doNotCloseAfterDecode() {
      this.closeAfterDecode = false;
      return this;
    }

    public Builder exceptionPropagationPolicy(ExceptionPropagationPolicy propagationPolicy) {
      this.propagationPolicy = propagationPolicy;
      return this;
    }

    public <T> T target(Class<T> apiType, String url) {
      return target(new HardCodedTarget<T>(apiType, url));
    }

    public <T> T target(Target<T> target) { // 生成代理类（不管@FeignCLient是否配置url，都会到这里统一生成代理对象）
      return build().newInstance(target); // 先构建出Feign对象实例--ReflectiveFeign，再创建代理对象
    }

    public Feign build() { // 构建Feign对象
      SynchronousMethodHandler.Factory synchronousMethodHandlerFactory =
          new SynchronousMethodHandler.Factory(client, retryer, requestInterceptors, logger, // 创建SynchronousMethodHandler.Factory（将Feign.Builder中的属性传递给SynchronousMethodHandler.Factory）
              logLevel, decode404, closeAfterDecode, propagationPolicy);
      ParseHandlersByName handlersByName =
          new ParseHandlersByName(contract, options, encoder, decoder, queryMapEncoder, // 创建ParseHandlersByName（将Feign.Builder中的属性传递给ParseHandlersByName）
              errorDecoder, synchronousMethodHandlerFactory); // ParseHandlersByName将Target的所有接口方法转换为Map<String, MethodHandler>对象
      return new ReflectiveFeign(handlersByName, invocationHandlerFactory, queryMapEncoder); // 创建ReflectiveFeign
    }
  }

  static class ResponseMappingDecoder implements Decoder {

    private final ResponseMapper mapper;
    private final Decoder delegate;

    ResponseMappingDecoder(ResponseMapper mapper, Decoder decoder) {
      this.mapper = mapper;
      this.delegate = decoder;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
      return delegate.decode(mapper.map(response, type), type);
    }
  }
}
