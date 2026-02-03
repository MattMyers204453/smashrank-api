package com.smashrank.smashrank_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

@SpringBootApplication()
public class SmashrankApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmashrankApiApplication.class, args);
	}

}
