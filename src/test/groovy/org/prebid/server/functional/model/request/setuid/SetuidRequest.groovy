package org.prebid.server.functional.model.request.setuid

import com.fasterxml.jackson.annotation.JsonProperty
import groovy.transform.ToString
import org.prebid.server.functional.model.bidder.BidderName
import org.prebid.server.functional.model.request.Format

import static org.prebid.server.functional.model.bidder.BidderName.GENERIC

@ToString(includeNames = true, ignoreNulls = true)
class SetuidRequest {

    BidderName bidder
    String uid
    String gdpr
    @JsonProperty("gdpr_consent")
    String gdprConsent
    @JsonProperty("gpp_sid")
    String gppSid
    String gpp
    @JsonProperty("f")
    Format format
    String account

    static SetuidRequest getDefaultSetuidRequest() {
        new SetuidRequest().tap {
            bidder = GENERIC
            gdpr = "0"
        }
    }
}
