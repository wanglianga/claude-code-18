package cn.county.agrimach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class AgrimachApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgrimachApplication.class, args);
    }
}
