package com.sanikasurose.throttle.web;

import com.sanikasurose.throttle.core.RateLimiter;
import com.sanikasurose.throttle.metrics.RateLimiterMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.anyBoolean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice-test for {@link RateLimitFilter}.
 *
 * <p>{@link WebMvcTest} loads only the web layer: {@link MetricsController},
 * {@link RateLimitFilter}, and {@link ThrottleConfig}. Because {@code ThrottleConfig}
 * lives in the same package it is picked up by the slice scan. {@link MockBean} for
 * {@link RateLimiter} and {@link RateLimiterMetrics} override those bean definitions
 * before the context starts, so no Redis connection is ever attempted and the
 * collaborators' behaviour is fully controlled per-test.
 *
 * <p>All requests exercise {@code GET /ping} (handled by {@link MetricsController#ping()})
 * as a neutral, always-200 target endpoint. {@code GET /metrics} is used exclusively
 * to verify filter-exemption behaviour.
 */
@WebMvcTest
class RateLimitFilterTest {

    private static final String USER_ID        = "alice";
    private static final String USER_ID_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private RateLimiterMetrics rateLimiterMetrics;

    @BeforeEach
    void setUp() {
        // Default: every request is within the rate limit.
        when(rateLimiter.allowRequest(anyString())).thenReturn(true);
        // getSummary() is needed by GET /metrics via MetricsController.
        when(rateLimiterMetrics.getSummary()).thenReturn(Map.of());
    }

    // =========================================================================
    // Happy path — valid header, within limit
    // =========================================================================

    @Test
    void requestWithValidUserId_returns200() throws Exception {
        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID))
               .andExpect(status().isOk());
    }

    @Test
    void requestWithValidUserId_invokesRateLimiterWithUserId() throws Exception {
        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID));

        verify(rateLimiter).allowRequest(USER_ID);
    }

    @Test
    void requestWithValidUserId_recordsAllowedOutcomeInMetrics() throws Exception {
        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID));

        verify(rateLimiterMetrics).record(USER_ID, true);
    }

    // =========================================================================
    // Missing / blank X-User-Id header → 400 Bad Request
    // =========================================================================

    @Test
    void requestWithoutUserIdHeader_returns400() throws Exception {
        mockMvc.perform(get("/ping"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void requestWithBlankUserIdHeader_returns400() throws Exception {
        // Tests the isBlank() guard, not just the null guard.
        mockMvc.perform(get("/ping").header(USER_ID_HEADER, "   "))
               .andExpect(status().isBadRequest());
    }

    @Test
    void missingHeader_responseBodyContainsExpectedErrorMessage() throws Exception {
        mockMvc.perform(get("/ping"))
               .andExpect(jsonPath("$.error").value("Missing required header: " + USER_ID_HEADER));
    }

    @Test
    void missingHeader_responseContentTypeIsJson() throws Exception {
        mockMvc.perform(get("/ping"))
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void missingHeader_rateLimiterIsNotInvoked() throws Exception {
        mockMvc.perform(get("/ping"));

        verify(rateLimiter, never()).allowRequest(anyString());
    }

    @Test
    void missingHeader_metricsAreNotRecorded() throws Exception {
        mockMvc.perform(get("/ping"));

        verify(rateLimiterMetrics, never()).record(anyString(), anyBoolean());
    }

    // =========================================================================
    // Rate limit exceeded → 429 Too Many Requests
    // =========================================================================

    @Test
    void requestExceedingRateLimit_returns429() throws Exception {
        when(rateLimiter.allowRequest(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID))
               .andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitExceeded_responseBodyContainsUserIdAndErrorMessage() throws Exception {
        when(rateLimiter.allowRequest(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID))
               .andExpect(jsonPath("$.error").value("Rate limit exceeded"))
               .andExpect(jsonPath("$.userId").value(USER_ID));
    }

    @Test
    void rateLimitExceeded_responseContentTypeIsJson() throws Exception {
        when(rateLimiter.allowRequest(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID))
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void rateLimitExceeded_recordsRejectedOutcomeInMetrics() throws Exception {
        when(rateLimiter.allowRequest(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/ping").header(USER_ID_HEADER, USER_ID));

        verify(rateLimiterMetrics).record(USER_ID, false);
    }

    // =========================================================================
    // /metrics endpoint — exempt from rate limiting via shouldNotFilter()
    // =========================================================================

    @Test
    void metricsEndpoint_isExemptFromRateLimiting_returns200() throws Exception {
        // No X-User-Id header is supplied. If the filter ran it would return 400.
        // Because shouldNotFilter() returns true for /metrics, the filter is
        // bypassed entirely and MetricsController returns 200.
        mockMvc.perform(get("/metrics"))
               .andExpect(status().isOk());
    }

    @Test
    void metricsEndpoint_rateLimiterIsNeverInvoked() throws Exception {
        // shouldNotFilter() uses startsWith("/metrics"), so the entire filter
        // pipeline — including the rateLimiter call — is skipped.
        mockMvc.perform(get("/metrics"));

        verify(rateLimiter, never()).allowRequest(anyString());
    }
}
