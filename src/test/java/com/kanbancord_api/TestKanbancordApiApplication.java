package com.kanbancord_api;

import org.springframework.boot.SpringApplication;

public class TestKanbancordApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(KanbancordApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
