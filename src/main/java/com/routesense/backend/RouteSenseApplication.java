package com.routesense.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RouteSenseApplication {

	public static void main(String[] args) {
		SpringApplication.run(RouteSenseApplication.class, args);
	}

}
