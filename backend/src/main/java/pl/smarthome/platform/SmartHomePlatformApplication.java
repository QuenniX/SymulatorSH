package pl.smarthome.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SmartHomePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartHomePlatformApplication.class, args);
    }
}
