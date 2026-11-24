package ru.taksi.quote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Риск: документация Swagger расходится с реальными ответами API
 * (раньше POST /quotes был описан как 200, а 422 показывался как «Undocumented»).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ru.taksi.support.TestClockConfig.class)
class OpenApiContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createQuoteDocumentsAllResponseCodes() throws Exception {
        String post = "$.paths['/api/v1/quotes'].post.responses";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(post, hasKey("201")))
                .andExpect(jsonPath(post, hasKey("400")))
                .andExpect(jsonPath(post, hasKey("422")))
                .andExpect(jsonPath(post, not(hasKey("200"))))
                .andExpect(jsonPath(post + "['422'].content['application/problem+json'].examples.ROUTE_TOO_LONG").exists())
                .andExpect(jsonPath(post + "['422'].content['application/problem+json'].examples.OUTSIDE_SERVICE_AREA").exists());
    }

    @Test
    void getQuoteDocumentsAllResponseCodes() throws Exception {
        String getOp = "$.paths['/api/v1/quotes/{id}'].get.responses";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(getOp, hasKey("200")))
                .andExpect(jsonPath(getOp, hasKey("400")))
                .andExpect(jsonPath(getOp, hasKey("404")));
    }
}
