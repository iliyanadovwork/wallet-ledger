package com.iliyanadov.walletledger.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Time comes from a bean so a test can hold it still.
 *
 * <p>The health check reports how long settlement has been failing, and a test of that has to be
 * able to say "now it is three minutes later" without waiting three minutes.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
