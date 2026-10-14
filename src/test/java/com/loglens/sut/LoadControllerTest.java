package com.loglens.sut;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LoadControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void health() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void echo() throws Exception {
        mvc.perform(get("/echo")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void delay() throws Exception {
        mvc.perform(get("/delay?ms=10")).andExpect(status().isOk()).andExpect(jsonPath("$.ms").value(10));
        mvc.perform(get("/delay?ms=30001")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/delay?ms=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/delay?ms=abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void cpu() throws Exception {
        mvc.perform(get("/cpu?n=1000")).andExpect(status().isOk()).andExpect(jsonPath("$.result").isString());
        mvc.perform(get("/cpu?n=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/cpu?n=1000001")).andExpect(status().isBadRequest());
    }

    @Test
    void payload() throws Exception {
        mvc.perform(get("/payload?kb=100"))
                .andExpect(status().isOk())
                .andExpect(header().longValue("Content-Length", 100 * 1024))
                .andExpect(content().contentType("application/octet-stream"));
        mvc.perform(get("/payload?kb=0")).andExpect(status().isOk());
        mvc.perform(get("/payload?kb=10241")).andExpect(status().isBadRequest());
    }
}
