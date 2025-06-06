package org.prebid.server.auction;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.StoredResponseResult;
import org.prebid.server.auction.model.TimeoutContext;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredAuctionResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidResponse;
import org.prebid.server.proto.openrtb.ext.response.ExtBidderError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.prebid.server.bidder.model.BidderError.Type.generic;

@ExtendWith(MockitoExtension.class)
public class SkippedAuctionServiceTest {

    @Mock
    private StoredResponseProcessor storedResponseProcessor;
    @Mock
    private Timeout timeout;

    private SkippedAuctionService target;

    @BeforeEach
    public void setUp() {
        target = new SkippedAuctionService(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnFailedFutureWhenRequestIsRejected() {
        // given
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .requestRejected(true)
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).hasMessage("Rejected request cannot be skipped");
        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnFailedFutureWhenBidRequestExtIsNull() {
        // given
        final AuctionContext auctionContext = AuctionContext.builder()
                .bidRequest(BidRequest.builder().ext(null).build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(auctionContext);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .hasMessage("the auction can not be skipped, ext.prebid.storedauctionresponse is absent");

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnFailedFutureWhenBidRequestExtPrebidIsNull() {
        // given
        final AuctionContext auctionContext = AuctionContext.builder()
                .bidRequest(BidRequest.builder().ext(ExtRequest.empty()).build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(auctionContext);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .hasMessage("the auction can not be skipped, ext.prebid.storedauctionresponse is absent");

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnFailedFutureWhenStoredResponseIsNull() {
        // given
        final AuctionContext auctionContext = AuctionContext.builder()
                .bidRequest(BidRequest.builder()
                        .ext(ExtRequest.of(ExtRequestPrebid.builder().storedAuctionResponse(null).build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(auctionContext);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .hasMessage("the auction can not be skipped, ext.prebid.storedauctionresponse is absent");

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnFailedFutureWhenStoredResponseSeatBidAndIdAreNull() {
        // given
        final AuctionContext auctionContext = AuctionContext.builder()
                .bidRequest(BidRequest.builder()
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(ExtStoredAuctionResponse.of(null, null, null))
                                .build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(auctionContext);

        // then
        assertThat(result.failed()).isTrue();
        assertThat(result.cause())
                .hasMessage("the auction can not be skipped, "
                        + "ext.prebid.storedauctionresponse can not be resolved properly");

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnBidResponseWithSeatBidsFromStoredAuctionResponse() {
        // given
        final List<SeatBid> givenSeatBids = givenSeatBids("bidId1", "bidId2");
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of("id", givenSeatBids, null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(singletonList("no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(givenSeatBids)
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(ExtBidderError.of(
                                        generic.getCode(),
                                        "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnEmptySeatBidsWhenSeatBidIsNull() {
        // given
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of(
                "id", singletonList(null), null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(List.of(
                        "SeatBid can't be null in stored response",
                        "no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(emptyList())
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(
                                        ExtBidderError.of(generic.getCode(),
                                                "SeatBid can't be null in stored response"),
                                        ExtBidderError.of(generic.getCode(),
                                                "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnEmptySeatBidsWhenSeatIsEmpty() {
        // given
        final List<SeatBid> givenSeatBids = singletonList(SeatBid.builder().seat("").build());
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of("id", givenSeatBids, null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(List.of(
                        "Seat can't be empty in stored response seatBid",
                        "no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(emptyList())
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(
                                        ExtBidderError.of(generic.getCode(),
                                                "Seat can't be empty in stored response seatBid"),
                                        ExtBidderError.of(generic.getCode(),
                                                "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnEmptySeatBidsWhenBidsAreEmpty() {
        // given
        final List<SeatBid> givenSeatBids = singletonList(SeatBid.builder().seat("seat").bid(emptyList()).build());
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of("id", givenSeatBids, null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(List.of(
                        "There must be at least one bid in stored response seatBid",
                        "no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(emptyList())
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(
                                        ExtBidderError.of(generic.getCode(),
                                                "There must be at least one bid in stored response seatBid"),
                                        ExtBidderError.of(generic.getCode(),
                                                "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());

        verifyNoInteractions(storedResponseProcessor);
    }

    @Test
    public void skipAuctionShouldReturnBidResponseWithEmptySeatBidsWhenNoValueAvailableById() {
        // given
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of("id", null, null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .timeoutContext(TimeoutContext.of(1000L, timeout, 0))
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        given(storedResponseProcessor.getStoredResponseResult("id", timeout))
                .willReturn(Future.failedFuture("no value"));

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(List.of(
                        "no value",
                        "no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(emptyList())
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(
                                        ExtBidderError.of(generic.getCode(), "no value"),
                                        ExtBidderError.of(generic.getCode(),
                                                "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());
    }

    @Test
    public void skipAuctionShouldReturnBidResponseWithStoredSeatBidsByProvidedId() {
        // given
        final List<SeatBid> givenSeatBids = givenSeatBids("bidId1", "bidId2");
        final ExtStoredAuctionResponse givenStoredResponse = ExtStoredAuctionResponse.of("id", null, null);
        final AuctionContext givenAuctionContext = AuctionContext.builder()
                .debugWarnings(new ArrayList<>())
                .timeoutContext(TimeoutContext.of(1000L, timeout, 0))
                .bidRequest(BidRequest.builder()
                        .id("requestId")
                        .tmax(1000L)
                        .cur(List.of("USD"))
                        .ext(ExtRequest.of(ExtRequestPrebid.builder()
                                .storedAuctionResponse(givenStoredResponse)
                                .build()))
                        .build())
                .build();

        given(storedResponseProcessor.getStoredResponseResult("id", timeout))
                .willReturn(Future.succeededFuture(StoredResponseResult.of(null, givenSeatBids, null)));

        // when
        final Future<AuctionContext> result = target.skipAuction(givenAuctionContext);

        // then
        final AuctionContext expectedAuctionContext = givenAuctionContext.toBuilder()
                .debugWarnings(singletonList("no auction. response defined by storedauctionresponse"))
                .bidResponse(BidResponse.builder()
                        .id("requestId")
                        .cur("USD")
                        .seatbid(givenSeatBids)
                        .ext(ExtBidResponse.builder()
                                .tmaxrequest(1000L)
                                .warnings(singletonMap("prebid", List.of(ExtBidderError.of(
                                        generic.getCode(),
                                        "no auction. response defined by storedauctionresponse"))))
                                .build())
                        .build())
                .build();

        assertThat(result.succeeded()).isTrue();
        assertThat(result.result()).isEqualTo(expectedAuctionContext.skipAuction());
    }

    private static List<SeatBid> givenSeatBids(String... bidIds) {
        return Arrays.stream(bidIds)
                .map(bidId -> SeatBid.builder()
                        .seat("seat")
                        .bid(singletonList(Bid.builder().id(bidId).build()))
                        .build())
                .toList();

    }

}
