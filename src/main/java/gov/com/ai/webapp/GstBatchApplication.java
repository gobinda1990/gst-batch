package gov.com.ai.webapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class GstBatchApplication {

	public static void main(String[] args) {
		SpringApplication.run(GstBatchApplication.class, args);
	}

}
