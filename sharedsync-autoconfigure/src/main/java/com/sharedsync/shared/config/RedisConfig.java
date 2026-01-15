package com.sharedsync.shared.config;

import java.time.Duration;
import java.util.Set;

import org.reflections.Reflections;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sharedsync.shared.annotation.Cache;
import com.sharedsync.shared.dto.CacheDto;
import com.sharedsync.shared.repository.CacheStore;
import com.sharedsync.shared.repository.RedisCacheStore;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;

/**
 * Redis 캐시 설정.
 * sharedsync.cache.type=redis 일 때만 활성화됩니다.
 * 기본값은 인메모리 캐시를 사용합니다.
 */
@EnableCaching
@Configuration
@ConditionalOnProperty(name = "sharedsync.cache.type", havingValue = "redis")
public class RedisConfig implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware {

    private String basePackage = "com"; // fallback 기본값

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        String mainClassName = applicationContext.getEnvironment().getProperty("sun.java.command");
        if (mainClassName != null) {
            try {
                Class<?> mainClass = Class.forName(mainClassName.split(" ")[0]);
                Package mainPackage = mainClass.getPackage();
                if (mainPackage != null) {
                    basePackage = mainPackage.getName();
                }
            } catch (Exception e) {
                basePackage = "com";
            }
        }
    }

    @Bean
    public RedisTemplate<String, Integer> refreshRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Integer> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") String portStr,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.sentinel.master:}") String sentinelMaster,
            @Value("${spring.data.redis.sentinel.nodes:}") String sentinelNodes
    ) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(ReadFrom.REPLICA_PREFERRED)
                .clientOptions(ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build())
                .commandTimeout(Duration.ofSeconds(5))
                .build();

        // Sentinel 구성이 있는 경우 우선 처리
        if (sentinelMaster != null && !sentinelMaster.isEmpty() && sentinelNodes != null && !sentinelNodes.isEmpty()) {
            RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
                    .master(sentinelMaster);
            
            String[] nodes = sentinelNodes.split(",");
            for (String node : nodes) {
                String[] parts = node.split(":");
                String sHost = parts[0];
                int sPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 26379;
                sentinelConfig.sentinel(sHost, sPort);
            }
            
            if (password != null && !password.isEmpty()) {
                sentinelConfig.setPassword(RedisPassword.of(password));
                sentinelConfig.setSentinelPassword(RedisPassword.of(password));
            }
            LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfig, clientConfig);
            factory.afterPropertiesSet();
            return factory;
        }

        // Standalone 구성 (기존 방식)
        int port = 6379;
        try {
            // K8s 환경에서 REDIS_PORT가 "tcp://..." 값으로 들어오는 경우 대비
            if (portStr != null && !portStr.startsWith("tcp://")) {
                port = Integer.parseInt(portStr);
            }
        } catch (NumberFormatException e) {
            port = 6379;
        }

        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isEmpty()) {
            standaloneConfig.setPassword(RedisPassword.of(password));
        }
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public GenericJackson2JsonRedisSerializer redisValueSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    @Bean(name = "presenceRedis")
    public RedisTemplate<String, Object> presenceRedis(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        configureSerializers(template);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redis 기반 globalCacheStore 빈 등록
     * AutoCacheRepository에서 getCacheStore()가 이 빈을 우선적으로 사용합니다.
     */
    @Bean(name = "globalCacheStore")
    @SuppressWarnings("rawtypes")
    public CacheStore redisCacheStore(RedisConnectionFactory connectionFactory, GenericJackson2JsonRedisSerializer serializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        configureSerializers(template, serializer);
        template.afterPropertiesSet();
        System.out.println("[SharedSync] Using Redis cache store");
        return new RedisCacheStore<>(template);
    }



    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // Only scan sharedsync.dto for @Cache-annotated DTOs
        Reflections reflections = new Reflections("sharedsync.dto");
        Set<Class<?>> cacheDtos = reflections.getTypesAnnotatedWith(Cache.class);

        for (Class<?> dtoClass : cacheDtos) {
            if (!CacheDto.class.isAssignableFrom(dtoClass)) {
                continue;
            }

            String beanName = resolveBeanName(dtoClass);
            if (registry.containsBeanDefinition(beanName)) {
                continue;
            }

            GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
            beanDefinition.setBeanClass(DynamicRedisTemplateFactoryBean.class);
            beanDefinition.getConstructorArgumentValues().addGenericArgumentValue(dtoClass);
            beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);
            registry.registerBeanDefinition(beanName, beanDefinition);
        }
    }

    private String resolveBeanName(Class<?> dtoClass) {

        String simpleName = dtoClass.getSimpleName();
        if (simpleName.endsWith("Dto")) {
            simpleName = simpleName.substring(0, simpleName.length() - 3);
        }
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1) + "Redis";
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // no-op
    }

    private static void configureSerializers(RedisTemplate<String, ?> template) {
        configureSerializers(template, null);
    }

    private static void configureSerializers(
            RedisTemplate<String, ?> template,
            GenericJackson2JsonRedisSerializer customSerializer
    ) {
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer serializer =
                customSerializer != null ? customSerializer : new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setDefaultSerializer(serializer);
    }

    public static class DynamicRedisTemplateFactoryBean<T>
            implements FactoryBean<RedisTemplate<String, T>> {

        @SuppressWarnings("unused")
        private final Class<T> valueType;

        private RedisConnectionFactory connectionFactory;
        private GenericJackson2JsonRedisSerializer serializer;

        public DynamicRedisTemplateFactoryBean(Class<T> valueType) {
            this.valueType = valueType;
        }

        @Autowired
        public void setConnectionFactory(RedisConnectionFactory connectionFactory) {
            this.connectionFactory = connectionFactory;
        }

        @Autowired
        public void setSerializer(GenericJackson2JsonRedisSerializer serializer) {
            this.serializer = serializer;
        }

        @Override
        public RedisTemplate<String, T> getObject() {
            RedisTemplate<String, T> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);
            configureSerializers(template, serializer);
            template.afterPropertiesSet();
            return template;
        }

        @Override
        public Class<?> getObjectType() {
            return RedisTemplate.class;
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }
}
