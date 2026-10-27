package com.overload.overload_server.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import com.overload.overload_server.security.SecurityConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(SecurityConfiguration.class)
class HealthControllerTests {

	private final MockMvc mockMvc;

	@Autowired
	HealthControllerTests(MockMvc mockMvc) {
		this.mockMvc = mockMvc;
	}

	@Test
	void getHealthReturnsUp() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
	}

	@Test
	void postHealthReturnsMethodNotAllowed() throws Exception {
		mockMvc.perform(post("/api/v1/health"))
				.andExpect(status().isMethodNotAllowed());
	}

}
