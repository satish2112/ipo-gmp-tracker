package com.ipogmp.tracker.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InvestorGainIpo {
    private String name;
    private String type;   // "EQ" or "SME"
    private String gmp;
    private String price;
    private String subscriptionTimes;
    private String allotmentDate;
}
