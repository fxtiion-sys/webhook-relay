package com.example.webhook_relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WebhookRelayApplication {

	public static void main(String[] args) {

		SpringApplication.run(WebhookRelayApplication.class, args
		);
	}

}
