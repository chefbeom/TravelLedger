package com.playdata.calen.account.security;

import java.time.Duration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Opt-in shared login sessions using the existing state Redis settings. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.session.shared-enabled", havingValue = "true")
@EnableSpringHttpSession
public class SharedSessionConfig {
    @Bean
    LettuceConnectionFactory sessionRedisConnectionFactory(
            @Value("${app.redis.state.host:}") String host,
            @Value("${app.redis.state.port:6379}") int port,
            @Value("${app.redis.state.database:0}") int database,
            @Value("${app.redis.state.username:}") String username,
            @Value("${app.redis.state.password:}") String password,
            @Value("${app.redis.state.ssl:false}") boolean ssl) {
        if (host.isBlank()) throw new IllegalStateException("Shared sessions require REDIS_STATE_HOST.");
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
        server.setDatabase(database);
        if (!username.isBlank()) server.setUsername(username);
        if (!password.isBlank()) server.setPassword(password);
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(3)).build()).build())
                .commandTimeout(Duration.ofSeconds(3)).shutdownTimeout(Duration.ofSeconds(2));
        if (ssl) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    @Bean
    RedisSessionRepository sessionRepository(LettuceConnectionFactory sessionRedisConnectionFactory,
            @Value("${app.redis.state.key-prefix:}") String keyPrefix) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(sessionRedisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setDefaultSerializer(new JdkSerializationRedisSerializer());
        template.afterPropertiesSet();
        RedisSessionRepository repository = new RedisSessionRepository(template);
        repository.setRedisKeyNamespace(keyPrefix + "calen:sessions");
        repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
        return repository;
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer cookies = new DefaultCookieSerializer();
        cookies.setCookieName("JSESSIONID");
        cookies.setCookiePath("/");
        cookies.setUseSecureCookie(secure);
        cookies.setUseHttpOnlyCookie(true);
        cookies.setSameSite("Lax");
        return cookies;
    }
}
