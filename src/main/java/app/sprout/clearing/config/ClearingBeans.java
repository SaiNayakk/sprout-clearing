package app.sprout.clearing.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClearingBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
