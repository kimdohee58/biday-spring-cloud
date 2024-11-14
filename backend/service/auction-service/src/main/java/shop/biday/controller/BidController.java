package shop.biday.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import shop.biday.model.domain.BidModel;
import shop.biday.model.dto.BidDto;
import shop.biday.model.dto.BidResponse;
import shop.biday.service.AuctionService;
import shop.biday.service.BidService;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/bids")
@Tag(name = "bids", description = "Bid Controller")
public class BidController {

    private final Map<Long, Sinks.Many<BidResponse>> bidSinks = new ConcurrentHashMap<>();

    private final BidService bidService;
    private final AuctionService auctionService;

    @Operation(summary = "입찰 내역 조회", description = "마이페이지 입찰한 내역 전체 조회")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "입찰 가져오기 성공"),
            @ApiResponse(responseCode = "403", description = "권한 없음"),
            @ApiResponse(responseCode = "404", description = "잘못된 조회"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @Parameters({
            @Parameter(name = "UserInfo", description = "현재 로그인한 사용자 ",
                    example = "UserInfo{'id': 'abc342', 'name': 'kim', role: 'ROLE_USER'}"),
    })
    @GetMapping
    public Flux<BidDto> findByUserIdAndAuctionId(@RequestHeader("UserInfo") String userInfo) {
        log.info("findByUserIdAndAuctionId userInfo= {}", userInfo);
        return bidService.findByUserId(userInfo);
    }

    @Operation(summary = "입찰 조회", description = "auctionId로 최고 입찰가를 조회합니다.(SSE)")
    @Parameters({
            @Parameter(name = "auctionId", description = "경매 ID", example = "1"),
    })
    @ApiResponse(responseCode = "200", description = "성공")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<BidResponse> streamBid(@RequestParam("auctionId") Long auctionId) {
        log.info("stream auctionId: {}", auctionId);
        Sinks.Many<BidResponse> bidSink = bidSinks.computeIfAbsent(auctionId, id ->
                Sinks.many().multicast().onBackpressureBuffer());

        Mono<BidResponse> findBid = bidService.findTopBidByAuctionId(auctionId)
                .flatMap(bid -> bidService.countByAuctionId(auctionId)
                        .map(count -> BidResponse.builder()
                                .auctionId(bid.getAuctionId())
                                .currentBid(bid.getCurrentBid())
                                .award(bid.isAward())
                                .count(count)
                                .bidedAt(bid.getBidedAt())
                                .build())
                )
                .switchIfEmpty(
                        Mono.defer(() -> auctionService.findByAuctionId(auctionId)
                                .map(auction -> BidResponse.builder()
                                        .auctionId(auction.getId())
                                        .currentBid(BigInteger.valueOf(auction.getCurrentBid()))
                                        .award(false)
                                        .count(0L)
                                        .bidedAt(auction.getStartedAt())
                                        .build())
                        )
                );

        log.info("streamBid bidSinks: {}", bidSinks.keySet());

        return findBid.doOnNext(bidSink::tryEmitNext)
                .thenMany(bidSink.asFlux())
                .onErrorResume(IOException.class, e -> {
                    log.warn("IOException 발생 클라이언트 연결 끊김, auctionId: {}", auctionId);
                    if (bidSink.currentSubscriberCount() == 1) {
                        bidSinks.remove(auctionId);
                    }
                    return Flux.empty();
                })
                .timeout(Duration.ofMinutes(10))
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn("SSE 연결이 타임아웃되었습니다. auctionId: {}", auctionId);
                    if (bidSink.currentSubscriberCount() == 1) {
                        bidSinks.remove(auctionId);
                    }
                    return Flux.empty();
                })
                .doOnCancel(() -> {
                    log.warn("클라이언트가 연결을 끊었습니다. auctionId: {}", auctionId);
                    log.info("doOnCancel bidSink.currentSubscriberCount: {}", bidSink.currentSubscriberCount());
                    if (bidSink.currentSubscriberCount() == 1) {
                        bidSinks.remove(auctionId);
                        log.info("streamBid doOnCancel bindSinks remove: {}", bidSinks.keySet());
                    }
                }).log();
    }

    @Operation(summary = "입찰 저장", description = "입찰 데이터를 저장합니다.")
    @ApiResponse(responseCode = "200", description = "성공")
    @PostMapping
    @Parameters({
            @Parameter(name = "UserInfo", description = "현재 로그인한 사용자 ",
                    example = "UserInfo{'id': 'abc342', 'name': 'kim', role: 'ROLE_USER'}"),
            @Parameter(examples = {
                    @ExampleObject(name = "exampleBidModel", value = """ 
                                { 
                                    "auctionId" : "참여한 경매 id",
                                    "currentBid" : "입찰 가격"
                                } 
                            """)})
    })
    public Mono<BidResponse> save(@RequestHeader("UserInfo") String userInfo,
                                  @RequestBody @Validated BidModel bidModel) {
        log.info("save bidModel: {}, userInfo: {}", bidModel, userInfo);
        return bidService.save(userInfo, bidModel)
                .doOnNext(bid -> {
                    Sinks.Many<BidResponse> bidSink = bidSinks.get(bid.getAuctionId());
                    if (bidSink != null) {
                        bidSink.tryEmitNext(bid);
                    }
                });
    }

    public boolean sinkClose(Long auctionId) {
        if (bidSinks.containsKey(auctionId)) {
            Sinks.Many<BidResponse> bidSink = bidSinks.get(auctionId);
            bidSink.tryEmitComplete();
            bidSinks.remove(auctionId);
            return true;
        }
        return false;
    }
}