package com.ipogmp.tracker;

import com.ipogmp.tracker.model.InvestorGainIpo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvestorGainTest {

    @Test
    void investorGainIpoBuilder_setsAllFields() {
        InvestorGainIpo ipo = InvestorGainIpo.builder()
                .name("Test IPO")
                .gmp("120")
                .price("500")
                .subscriptionTimes("85.5x")
                .allotmentDate("15-Jun-2025")
                .build();

        assertEquals("Test IPO", ipo.getName());
        assertEquals("120", ipo.getGmp());
        assertEquals("500", ipo.getPrice());
        assertEquals("85.5x", ipo.getSubscriptionTimes());
        assertEquals("15-Jun-2025", ipo.getAllotmentDate());
    }

    @Test
    void investorGainIpoBuilder_handlesNullSubscriptionAndAllotment() {
        InvestorGainIpo ipo = InvestorGainIpo.builder()
                .name("Another IPO")
                .gmp("50")
                .price("300")
                .build();

        assertNotNull(ipo);
        assertNull(ipo.getSubscriptionTimes());
        assertNull(ipo.getAllotmentDate());
    }
}
