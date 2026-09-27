package com.team1.expo.client;

import com.team1.expo.common.TraceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class RestClientTicketClient implements TicketClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientTicketClient.class);

    private final RestClient restClient;
    private final String internalToken;

    public RestClientTicketClient(RestClient ticketRestClient,
                                  @Value("${internal.token}") String internalToken) {
        this.restClient = ticketRestClient;
        this.internalToken = internalToken;
    }

    @Override
    public Map<Long, Long> checkedInByRound(Long expoId) {
        try {
            CheckinSummaryItem[] body = restClient.get()
                    .uri("/internal/v1/tickets/checkin-summary?expoId={expoId}", expoId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken)
                    .header(TraceId.HEADER, TraceId.get())
                    .retrieve()
                    .body(CheckinSummaryItem[].class);
            Map<Long, Long> result = new HashMap<>();
            if (body != null) {
                for (CheckinSummaryItem item : body) {
                    result.put(item.roundId(), item.checkedIn());
                }
            }
            return result;
        } catch (Exception e) {
            // 체크인 수는 표시용이다. 못 받아도 예약 현황은 보여준다.
            log.warn("checkin-summary 호출 실패 expoId={} traceId={}", expoId, TraceId.get(), e);
            return null;
        }
    }

    private record CheckinSummaryItem(Long roundId, long checkedIn) {}
}
