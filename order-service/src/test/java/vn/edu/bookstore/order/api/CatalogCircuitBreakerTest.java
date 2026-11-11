package vn.edu.bookstore.order.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.edu.bookstore.order.TestcontainersConfig;
import vn.edu.bookstore.order.support.CatalogStub;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CatalogCircuitBreakerTest {

    static final WireMockServer CATALOG = CatalogStub.start();

    static {
        CATALOG.stubFor(
                com.github.tomakehurst.wiremock.client.WireMock
                        .get(com.github.tomakehurst.wiremock.client.WireMock
                                .urlMatching("/api/v1/books/.*"))
                        .willReturn(
                                com.github.tomakehurst.wiremock.client.WireMock
                                        .aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withFixedDelay(1500)
                                        .withBody("""
                                                {
                                                  "isbn": "9780134685991",
                                                  "title": "Effective Java",
                                                  "author": "Joshua Bloch",
                                                  "price": {
                                                    "amount": 285000.00,
                                                    "currency": "VND"
                                                  }
                                                }
                                                """)
                        )
        );
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("bookstore.catalog.base-url", CATALOG::baseUrl);
        r.add("bookstore.catalog.load-balanced", () -> false);
        r.add("bookstore.catalog.connect-timeout", () -> "1s");
        r.add("bookstore.catalog.read-timeout", () -> "300ms");

        r.add("bookstore.catalog.circuit-breaker.sliding-window-size", () -> 4);
        r.add("bookstore.catalog.circuit-breaker.minimum-number-of-calls", () -> 2);
        r.add("bookstore.catalog.circuit-breaker.failure-rate-threshold", () -> 50);
        r.add("bookstore.catalog.circuit-breaker.slow-call-duration-threshold", () -> "1500ms");
        r.add("bookstore.catalog.circuit-breaker.wait-duration-in-open-state", () -> "15s");
    }

    @AfterAll
    static void stop() {
        CATALOG.stop();
    }

    @Autowired
    MockMvc mvc;

    @Test
    void circuitBreakerMoSauHaiLanLoiVaChanBaLanConLai() throws Exception {

        String order = """
                {
                  "customerId": 88,
                  "lines": [
                    {
                      "isbn": "9780134685991",
                      "quantity": 1
                    }
                  ]
                }
                """;

        for (int i = 1; i <= 5; i++) {

            long start = System.nanoTime();

            MvcResult result = mvc.perform(
                            post("/api/v1/orders")
                                    .header("Idempotency-Key", "cb-test-" + i)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(order)
                    )
                    .andReturn();

            long elapsedMs =
                    (System.nanoTime() - start) / 1_000_000;

            assertEquals(
                    503,
                    result.getResponse().getStatus(),
                    "Lần gọi thứ " + i + " phải trả HTTP 503"
            );

            assertEquals(
                    "10",
                    result.getResponse().getHeader("Retry-After")
            );

            assertTrue(
                    result.getResponse().getContentAsString()
                            .contains("upstream-unavailable")
            );

            assertTrue(
                    elapsedMs < 1400,
                    "Lần gọi thứ " + i + " mất " + elapsedMs + " ms"
            );

            System.out.println(
                    "Lần gọi " + i + ": HTTP 503, thời gian "
                            + elapsedMs + " ms"
            );
        }

        assertEquals(
                2,
                CATALOG.getAllServeEvents().size(),
                "WireMock chỉ được nhận đúng 2 request"
        );
    }
}
