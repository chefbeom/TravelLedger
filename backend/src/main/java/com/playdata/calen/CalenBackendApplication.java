package com.playdata.calen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CalenBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CalenBackendApplication.class, args);
	}

}
