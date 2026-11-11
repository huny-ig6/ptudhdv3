package vn.edu.bookstore.order.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CatalogClientConfig {

    private static final Logger log =
            LoggerFactory.getLogger(CatalogClientConfig.class);

    @Bean
    RestClient catalogRestClient(RestClient.Builder builder, CatalogClientProperties props,
                                 ObjectProvider<DeferringLoadBalancerInterceptor> loadBalancer) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(props.readTimeout());

        builder.requestFactory(requestFactory);

        if (Boolean.TRUE.equals(props.loadBalanced())) {
            DeferringLoadBalancerInterceptor interceptor = loadBalancer.getIfAvailable();

            if (interceptor != null) {
                builder.requestInterceptor(interceptor);
            }
        }

        return builder.baseUrl(props.baseUrl()).build();
    }

    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> catalogCircuitBreaker(
            CatalogClientProperties props) {

        var cbProps = props.circuitBreaker();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(cbProps.slidingWindowSize())
                .minimumNumberOfCalls(cbProps.minimumNumberOfCalls())
                .failureRateThreshold(cbProps.failureRateThreshold())
                .slowCallDurationThreshold(cbProps.slowCallDurationThreshold())
                .waitDurationInOpenState(cbProps.waitDurationInOpenState())
                .ignoreExceptions(UnknownBookException.class)
                .build();

        return factory -> {
            factory.configure(
                    builder -> builder.circuitBreakerConfig(config),
                    "catalog"
            );

            factory.addCircuitBreakerCustomizer(
                    circuitBreaker -> circuitBreaker.getEventPublisher()
                            .onStateTransition(event ->
                                    log.info("Catalog Circuit Breaker chuyển trạng thái: {}",
                                            event.getStateTransition())),
                    "catalog"
            );
        };
    }
}
