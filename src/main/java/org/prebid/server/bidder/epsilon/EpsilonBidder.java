package org.prebid.server.bidder.epsilon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderCall;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.epsilon.ExtImpEpsilon;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.BidderUtil;
import org.prebid.server.util.HttpUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class EpsilonBidder implements Bidder<BidRequest> {

    private static final String BIDDER_CURRENCY = "USD";

    private final CurrencyConversionService currencyConversionService;

    private static final TypeReference<ExtPrebid<?, ExtImpEpsilon>> EPSILON_EXT_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    // List of API frameworks supported by the publisher
    private static final Set<Integer> APIS = IntStream.range(1, 7).boxed().collect(Collectors.toSet());

    // Options for the various bid response protocols that could be supported by an exchange
    private static final Set<Integer> PROTOCOLS = IntStream.range(1, 11).boxed().collect(Collectors.toSet());

    // Position of the ad as a relative measure of visibility or prominence
    private static final Set<Integer> AD_POSITIONS = IntStream.range(0, 8).boxed().collect(Collectors.toSet());

    private static final String DISPLAY_MANAGER = "prebid-s2s";
    private static final String DISPLAY_MANAGER_VER = "2.0.0";

    private final String endpointUrl;
    private final boolean generateBidId;
    private final JacksonMapper mapper;

    public EpsilonBidder(String endpointUrl,
                         boolean generateBidId,
                         JacksonMapper mapper,
                         CurrencyConversionService currencyConversionService) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.generateBidId = generateBidId;
        this.mapper = Objects.requireNonNull(mapper);
        this.currencyConversionService = Objects.requireNonNull(currencyConversionService);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final BidRequest outgoingRequest;
        try {
            outgoingRequest = createOutgoingRequest(bidRequest);
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput(e.getMessage()));
        }

        return Result.of(Collections.singletonList(BidderUtil.defaultRequest(outgoingRequest, endpointUrl, mapper)),
                Collections.emptyList());
    }

    private BidRequest createOutgoingRequest(BidRequest bidRequest) {
        final List<Imp> modifiedImps = new ArrayList<>();
        final List<Imp> requestImps = bidRequest.getImp();
        for (int i = 0; i < requestImps.size(); i++) {
            final Imp imp = requestImps.get(i);
            final ExtImpEpsilon impExt = parseImpExt(imp, i);
            final BigDecimal bidFloor = resolveBidFloor(bidRequest,
                    imp.getBidfloorcur(),
                    getBidFloor(imp.getBidfloor(), impExt.getBidfloor()));
            modifiedImps.add(modifyImp(imp, impExt, bidFloor));
        }

        final Imp firstImp = requestImps.getFirst();
        final ExtImpEpsilon extImp = parseImpExt(firstImp, 0);
        final String siteId = extImp.getSiteId();
        final Site requestSite = bidRequest.getSite();
        final App requestApp = bidRequest.getApp();
        return bidRequest.toBuilder()
                .site(updateSite(requestSite, siteId))
                .app(requestSite == null ? updateApp(requestApp, siteId) : requestApp)
                .imp(modifiedImps)
                .cur(Collections.singletonList(BIDDER_CURRENCY))
                .build();
    }

    private BigDecimal resolveBidFloor(BidRequest bidRequest, String bidfloorcur, BigDecimal bidfloor) {
        if (BidderUtil.isValidPrice(bidfloor)
                && !StringUtils.equalsIgnoreCase(bidfloorcur, BIDDER_CURRENCY)
                && StringUtils.isNotBlank(bidfloorcur)) {
            return currencyConversionService.convertCurrency(bidfloor, bidRequest, bidfloorcur, BIDDER_CURRENCY);
        }
        return bidfloor;
    }

    private ExtImpEpsilon parseImpExt(Imp imp, int impIndex) {
        final ExtImpEpsilon extImp;
        try {
            extImp = mapper.mapper().convertValue(imp.getExt(), EPSILON_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException("Impression[%d] missing ext.bidder object".formatted(impIndex));
        }

        if (StringUtils.isEmpty(extImp.getSiteId())) {
            throw new PreBidException("Impression[%d] requires ext.bidder.site_id".formatted(impIndex));
        }
        return extImp;
    }

    private static Site updateSite(Site site, String siteId) {
        return site == null ? null : site.toBuilder().id(siteId).build();
    }

    private static App updateApp(App app, String siteId) {
        return app == null ? null : app.toBuilder().id(siteId).build();
    }

    private static Imp modifyImp(Imp imp, ExtImpEpsilon impExt, BigDecimal bidfloor) {
        final Banner banner = imp.getBanner();
        final Video video = imp.getVideo();

        return imp.toBuilder()
                .displaymanager(DISPLAY_MANAGER)
                .displaymanagerver(DISPLAY_MANAGER_VER)
                .bidfloor(bidfloor)
                .bidfloorcur(BIDDER_CURRENCY)
                .tagid(getTagId(imp.getTagid(), impExt.getTagId()))
                .secure(getSecure(imp, impExt))
                .banner(modifyBanner(banner, impExt.getPosition()))
                .video(video != null && banner == null ? modifyVideo(video, impExt) : video)
                .build();
    }

    private static String getTagId(String tagId, String impExtTagId) {
        return StringUtils.isNotEmpty(impExtTagId) ? impExtTagId : tagId;
    }

    private static BigDecimal getBidFloor(BigDecimal impBidFloor, BigDecimal impExtBidFloor) {

        return BidderUtil.isValidPrice(impExtBidFloor) && !BidderUtil.isValidPrice(impBidFloor)
                ? impExtBidFloor
                : impBidFloor;
    }

    private static Integer getSecure(Imp imp, ExtImpEpsilon impExt) {
        final Integer extSecure = impExt.getSecure();
        final Integer impSecure = imp.getSecure();

        return extSecure != null && (BidderUtil.isNullOrZero(impSecure)) ? extSecure : impSecure;
    }

    private static Banner modifyBanner(Banner impBanner, Integer extPosition) {
        return impBanner == null
                ? null
                : impBanner.toBuilder()
                .pos(isValidPosition(extPosition) ? extPosition : null)
                .build();
    }

    private static Video modifyVideo(Video video, ExtImpEpsilon impExt) {
        final List<String> extMimes = impExt.getMimes();
        final Integer extMaxDuration = impExt.getMaxduration();
        final Integer extPosition = impExt.getPosition();
        return video.toBuilder()
                .mimes(CollectionUtils.isNotEmpty(extMimes) ? extMimes : video.getMimes())
                .maxduration(extMaxDuration != null ? extMaxDuration : video.getMaxduration())
                .pos(isValidPosition(extPosition) ? extPosition : null)
                .api(makeApi(impExt.getApi(), video.getApi()))
                .protocols(makeProtocols(impExt.getProtocols(), video.getProtocols()))
                .build();
    }

    private static boolean isValidPosition(Integer position) {
        return position != null && AD_POSITIONS.contains(position);
    }

    private static List<Integer> makeApi(List<Integer> extApi, List<Integer> videoApi) {
        final List<Integer> api = CollectionUtils.isNotEmpty(extApi) ? extApi : videoApi;
        return CollectionUtils.isNotEmpty(api)
                ? api.stream().filter(APIS::contains).toList()
                : videoApi;
    }

    private static List<Integer> makeProtocols(List<Integer> extProtocols, List<Integer> videoProtocols) {
        final List<Integer> protocols = CollectionUtils.isNotEmpty(extProtocols) ? extProtocols : videoProtocols;
        return CollectionUtils.isNotEmpty(protocols)
                ? protocols.stream().filter(PROTOCOLS::contains).toList()
                : videoProtocols;
    }

    @Override
    public Result<List<BidderBid>> makeBids(BidderCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            return Result.withValues(extractBids(httpCall));
        } catch (PreBidException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private List<BidderBid> extractBids(BidderCall<BidRequest> httpCall) {
        final BidResponse bidResponse;
        try {
            bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
        } catch (DecodeException e) {
            throw new PreBidException(e.getMessage());
        }
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            throw new PreBidException("Empty bid request");
        }
        return bidsFromResponse(httpCall.getRequest().getPayload(), bidResponse);
    }

    private List<BidderBid> bidsFromResponse(BidRequest bidRequest, BidResponse bidResponse) {
        final SeatBid firstSeatBid = bidResponse.getSeatbid().getFirst();
        final List<Bid> bids = firstSeatBid.getBid();

        if (CollectionUtils.isEmpty(bids)) {
            throw new PreBidException("Empty bids array");
        }
        return bids.stream()
                .filter(Objects::nonNull)
                .map(bid -> BidderBid.of(updateBidWithId(bid), getType(bid.getImpid(),
                        bidRequest.getImp()), bidResponse.getCur()))
                .toList();
    }

    private Bid updateBidWithId(Bid bid) {
        return generateBidId
                ? bid.toBuilder().id(UUID.randomUUID().toString()).build()
                : bid;
    }

    private static BidType getType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getAudio() != null) {
                    return BidType.audio;
                } else if (imp.getXNative() != null) {
                    return BidType.xNative;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                } else {
                    return BidType.banner;
                }
            }
        }
        return BidType.banner;
    }
}
