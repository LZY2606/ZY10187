package com.example.zeroledger;

import com.example.zeroledger.repository.WindTunnelRepository;
import com.example.zeroledger.service.FixtureService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WindTunnelApplication {
    public static void main(String[] args) {
        SpringApplication.run(WindTunnelApplication.class, args);
    }

    @Bean
    CommandLineRunner initialize(WindTunnelRepository repository, FixtureService fixtureService,
                                 @Value("${spring.datasource.url}") String datasourceUrl) {
        return args -> {
            Path databaseParent = Path.of("data");
            if (!datasourceUrl.contains(":memory:") && !Files.exists(databaseParent)) {
                Files.createDirectories(databaseParent);
            }
            repository.createSchema();
            if (repository.countRuns() == 0) {
                fixtureService.reset();
            }
        };
    }

}
