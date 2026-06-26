package com.ipogmp.tracker.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * Finnhub REST client — wraps the IPO Calendar endpoint.
 *
 * Endpoint : GET /calendar/ipo?from=YYYY-MM-DD&to=YYYY-MM-DD&token=KEY
 * Docs     : https://finnhub.io/docs/api/ipo-calendar
 * Free plan: 60 API calls / minute
 *
 * Coverage note: Finnhub's free-plan IPO calendar is primarily US (NYSE/NASDAQ).
 * Indian IPOs (NSE/BSE) appear when available but coverage is limited.
 * GMP is NOT available from Finnhub — that continues to come from InvestorGain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class  FinnhubService {

    private final RestTemplate restTemplate;

    @Value("${finnhub.api.key}")
    private String apiKey;

    @Value("${finnhub.api.base-url:https://finnhub.io/api/v1}")
    private String baseUrl;

    /**
     * Fetch the IPO calendar for the given date range.
     * Returns an empty list on any error — caller should handle gracefully.
     */
    public List<FinnhubIpo> fetchIpoCalendar(LocalDate from, LocalDate to) {
        String url = baseUrl + "/calendar/ipo?from={from}&to={to}&token={token}";
        log.info("Calling Finnhub IPO Calendar: {} → {}", from, to);
        try {
            ResponseEntity<IpoCalendarResponse> resp = restTemplate.getForEntity(
                url, IpoCalendarResponse.class,
                from.toString(), to.toString(), apiKey
            );
            IpoCalendarResponse body = resp.getBody();
            List<FinnhubIpo> ipos = (body != null && body.getIpoCalendar() != null)
                ? body.getIpoCalendar()
                : List.of();
            log.info("Finnhub returned {} IPO records", ipos.size());
            return ipos;

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Finnhub 401 — check your API key in finnhub.api.key");
            return List.of();
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Finnhub 429 — rate limit hit, will retry next scheduled run");
            return List.of();
        } catch (Exception e) {
            log.error("Finnhub API call failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Response DTOs ──────────────────────────────────────────────────────────

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpoCalendarResponse {
        @JsonProperty("ipoCalendar")
        private List<FinnhubIpo> ipoCalendar;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinnhubIpo {
        /** Expected IPO / listing date  "YYYY-MM-DD" */
        private String date;

        /** Exchange code e.g. "NASDAQ", "NYSE", "NSE" */
        private String exchange;

        /** Full company name */
        private String name;

        /** Number of shares offered */
        @JsonProperty("numberOfShares")
        private Long numberOfShares;

        /**
         * Price or price range as a string.
         * Examples: "500", "400-420", "$15.00-17.00"
         */
        private String price;

        /**
         * IPO status.
         * Known values: "expected", "priced", "filed", "withdrawn"
         */
        private String status;

        /** Ticker symbol */
        private String symbol;

        /** Total deal value (in the exchange's local currency) */
        @JsonProperty("totalSharesValue")
        private Double totalSharesValue;
    }
}
