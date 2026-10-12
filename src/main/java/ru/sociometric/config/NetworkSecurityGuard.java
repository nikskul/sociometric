package ru.sociometric.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/** Refuses unsafe network exposure caused by a configuration typo. */
@Component
public class NetworkSecurityGuard implements ApplicationRunner {
    private final Environment environment;

    public NetworkSecurityGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        InetAddress address = InetAddress.getByName(environment.getProperty("server.address", "127.0.0.1"));
        if (!address.isLoopbackAddress()) {
            if (!environment.getProperty("sociometric.security.enabled", Boolean.class, false)) {
                throw new IllegalStateException("При сетевом доступе включите SOCIOMETRIC_AUTH_ENABLED и задайте учётные данные администратора");
            }
            if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
                throw new IllegalStateException("Не включайте H2 Console при сетевом доступе");
            }
        }
    }
}
