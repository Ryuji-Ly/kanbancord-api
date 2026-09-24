package com.kanbancord_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import java.util.TimeZone;

@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class KanbancordApiApplication {

	public static void main(String[] args) {
		// Timestamps are stored and sent without a zone and are UTC, as in the production image; this
		// keeps them UTC on machines set to another zone too. The web app reads them as UTC.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(KanbancordApiApplication.class, args);
	}

}
