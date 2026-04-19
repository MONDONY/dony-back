package com.dony.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// WebEnvironment.NONE : pas de Tomcat → shutdown propre → JaCoCo écrit son exec file
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DonyBackApplicationTests {

	@Test
	void contextLoads() {
	}
}
