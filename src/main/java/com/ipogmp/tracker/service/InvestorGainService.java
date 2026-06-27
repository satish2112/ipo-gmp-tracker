package com.ipogmp.tracker.service;

import com.ipogmp.tracker.model.InvestorGainIpo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestorGainService {

    private final RestTemplate restTemplate;

    public List<InvestorGainIpo> fetchIpoData() {
        String url = "https://www.investorgain.com/report/ipo-gmp-live/331/";
        log.info("Fetching data from {}", url);
        String html = restTemplate.getForObject(url, String.class);
        if (html != null) {
            log.info("Successfully fetched data from {}", url);
            return parseIpoData(html);
        }
        return new ArrayList<>();
    }

    private List<InvestorGainIpo> parseIpoData(String html) {
        List<InvestorGainIpo> ipos = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Element table = doc.select("table.table-bordered").first();
        if (table != null) {
            Elements rows = table.select("tr");
            for (int i = 1; i < rows.size(); i++) {
                Element row = rows.get(i);
                Elements cols = row.select("td");
                if (cols.size() > 5) {
                    try {
                        String ipoName = cols.get(0).text();

                        // col 1 may contain IPO type (EQ / SME / MainBoard)
                        String rawType = cols.size() > 1 ? cols.get(1).text().trim().toUpperCase() : "";
                        String type = rawType.contains("SME") ? "SME" : "EQ";

                        String gmp   = cols.get(2).text();
                        String price = cols.get(3).text();

                        // col 7 = allotment date, col 11 = subscription (may vary by page version)
                        String allotmentDate     = cols.size() > 7  ? cols.get(7).text()  : null;
                        String subscriptionTimes = cols.size() > 11 ? cols.get(11).text() : null;

                        InvestorGainIpo ipo = InvestorGainIpo.builder()
                                .name(ipoName)
                                .type(type)
                                .gmp(gmp)
                                .price(price)
                                .allotmentDate(allotmentDate)
                                .subscriptionTimes(subscriptionTimes)
                                .build();
                        ipos.add(ipo);
                    } catch (Exception e) {
                        log.error("Error parsing row: {}", row, e);
                    }
                }
            }
        } else {
            log.warn("Could not find the data table on the page.");
        }
        return ipos;
    }
}
