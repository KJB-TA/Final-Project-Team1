package com.team1.expo.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.team1.expo.client.ReservationClient.ReservationSummaryItem;
import com.team1.expo.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ReservationSummaryApiTest extends ApiTestSupport {

    private long ownerId;
    private String ownerToken;
    private long expoId;

    @BeforeEach
    void setUp() {
        ownerId = uniqueUserId();
        ownerToken = jwtFor(ownerId, "ORGANIZER");

        ResponseEntity<JsonNode> channelRes = post("/api/v1/channels",
                """
                {"name":"%s","description":"예약 현황 테스트"}
                """.formatted(uniqueName()), ownerToken);
        long channelId = channelRes.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> expoRes = post("/api/v1/channels/" + channelId + "/expos",
                """
                {"title":"예약 현황 박람회","category":"IT·전자","description":"설명","venue":"코엑스","region":"서울"}
                """, ownerToken);
        expoId = expoRes.getBody().path("data").path("id").asLong();
    }

    @Test
    @DisplayName("주최자가 본인 박람회 예약 현황을 조회하면 200과 회차별 집계를 반환한다")
    void 예약_현황_조회_성공() {
        when(reservationClient.getSummary(expoId)).thenReturn(List.of(
                new ReservationSummaryItem(1L, 100, 30, 5),
                new ReservationSummaryItem(2L, 50, 10, 1)
        ));

        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rounds = response.getBody().path("data").path("rounds");
        assertThat(rounds.size()).isEqualTo(2);
        assertThat(rounds.get(0).path("roundId").asLong()).isEqualTo(1L);
        assertThat(rounds.get(0).path("confirmed").asInt()).isEqualTo(30);
        assertThat(rounds.get(0).path("cancelled").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("회차별 체크인 수를 합친다 - 체크인이 없는 회차는 0")
    void 체크인_수_병합() {
        when(reservationClient.getSummary(expoId)).thenReturn(List.of(
                new ReservationSummaryItem(1L, 100, 30, 5),
                new ReservationSummaryItem(2L, 50, 10, 1)
        ));
        when(ticketClient.checkedInByRound(expoId)).thenReturn(Map.of(1L, 12L));

        JsonNode rounds = get("/api/v1/expos/" + expoId + "/reservations/summary", ownerToken)
                .getBody().path("data").path("rounds");

        assertThat(rounds.get(0).path("checkedIn").asInt()).isEqualTo(12);
        assertThat(rounds.get(1).path("checkedIn").asInt()).isZero();
    }

    @Test
    @DisplayName("체크인 집계를 못 받아도 200 이고 체크인 칸만 비운다")
    void 체크인_집계_실패_부분_허용() {
        when(reservationClient.getSummary(expoId)).thenReturn(List.of(
                new ReservationSummaryItem(1L, 100, 30, 5)
        ));
        when(ticketClient.checkedInByRound(expoId)).thenReturn(null);

        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode round = response.getBody().path("data").path("rounds").get(0);
        assertThat(round.path("confirmed").asInt()).isEqualTo(30);
        assertThat(round.path("checkedIn").isNull() || round.path("checkedIn").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("회차가 없으면 빈 배열을 반환한다")
    void 회차_없는_박람회_빈배열() {
        when(reservationClient.getSummary(expoId)).thenReturn(List.of());

        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", ownerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("data").path("rounds").size()).isEqualTo(0);
    }

    @Test
    @DisplayName("타인의 박람회를 조회하면 403을 반환한다")
    void 타인_박람회_조회_거절() {
        String otherToken = jwtFor(uniqueUserId(), "ORGANIZER");

        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", otherToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("JWT 없이 조회하면 401을 반환한다")
    void 인증_없이_조회_거절() {
        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("USER 역할로 조회하면 403을 반환한다")
    void USER_역할_조회_거절() {
        String userToken = jwtFor(ownerId, "USER");

        ResponseEntity<JsonNode> response = get("/api/v1/expos/" + expoId + "/reservations/summary", userToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
