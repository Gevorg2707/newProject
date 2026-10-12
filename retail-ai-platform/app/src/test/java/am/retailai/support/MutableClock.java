package am.retailai.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** A clock the test sets explicitly. Import {@link Config} to make it the application's clock. */
public final class MutableClock extends Clock {

    private static final ZoneId YEREVAN = ZoneId.of("Asia/Yerevan");
    private Instant now = Instant.now();

    public void setDate(LocalDate date) {
        now = date.atTime(12, 0).atZone(YEREVAN).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return YEREVAN;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
