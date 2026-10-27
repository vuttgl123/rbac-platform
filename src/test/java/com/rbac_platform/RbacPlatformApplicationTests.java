package com.rbac_platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class RbacPlatformApplicationTests {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AppDatabase.register(registry);
	}

	@Test
	void contextLoads() {
	}

}
