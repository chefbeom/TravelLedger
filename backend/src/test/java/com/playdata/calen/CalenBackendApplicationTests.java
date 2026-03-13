package com.playdata.calen;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"app.seed.enabled=true",
		"spring.datasource.url=jdbc:h2:mem:calen-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
class CalenBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
