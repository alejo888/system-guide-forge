package com.systemguideforge.backend.web;

import com.systemguideforge.backend.application.LocalUrlValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConfigControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exposesWhetherTheDockerHostIsAllowed(boolean allowDockerHost) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigController(new LocalUrlValidator(allowDockerHost))).build();

        mvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowDockerHost").value(allowDockerHost))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
