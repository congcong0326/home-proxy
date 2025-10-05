package org.congcong.controlmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class ControlManagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControlManagerApplication.class, args);
	}

}
