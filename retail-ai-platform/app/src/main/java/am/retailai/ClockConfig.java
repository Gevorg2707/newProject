package am.retailai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** "Today" for freshness rules comes from this clock, so tests can pin it instead of depending on the run date. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Yerevan"));
    }
}
