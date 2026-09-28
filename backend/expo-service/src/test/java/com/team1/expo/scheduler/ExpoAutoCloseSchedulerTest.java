package com.team1.expo.scheduler;

import com.team1.expo.client.RoundClient;
import com.team1.expo.domain.channel.Channel;
import com.team1.expo.domain.channel.ChannelRepository;
import com.team1.expo.domain.expo.Expo;
import com.team1.expo.domain.expo.ExpoRepository;
import com.team1.expo.domain.expo.ExpoStatus;
import com.team1.expo.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class ExpoAutoCloseSchedulerTest extends IntegrationTestSupport {

    @Autowired
    private ExpoAutoCloseScheduler scheduler;

    @Autowired
    private ExpoRepository expoRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @MockBean
    private RoundClient roundClient;

    @Test
    @DisplayName("모든 회차가 종료된 PUBLISHED 박람회는 CLOSED로 전이된다")
    void PUBLISHED_박람회_자동_마감() {
        Channel channel = channelRepository.save(Channel.create(uniqueName(), uniqueUserId(), "desc"));
        Expo expo = expoRepository.save(publishedExpo(channel.getId()));

        when(roundClient.finishedExpoIds(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(List.of(expo.getId()));

        scheduler.closeFinishedExpos();

        Expo result = expoRepository.findById(expo.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(ExpoStatus.CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("HIDDEN 박람회는 finishedExpoIds에 포함되더라도 CLOSED로 전이되지 않는다")
    void HIDDEN_박람회_마감_제외() {
        Channel channel = channelRepository.save(Channel.create(uniqueName(), uniqueUserId(), "desc"));
        Expo expo = expoRepository.save(hiddenExpo(channel.getId()));

        when(roundClient.finishedExpoIds(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(List.of(expo.getId()));

        scheduler.closeFinishedExpos();

        Expo result = expoRepository.findById(expo.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(ExpoStatus.HIDDEN);
        assertThat(result.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("스케줄러를 두 번 실행해도 closed_at이 변경되지 않는다 (멱등성)")
    void 멱등성_보장() {
        Channel channel = channelRepository.save(Channel.create(uniqueName(), uniqueUserId(), "desc"));
        Expo expo = expoRepository.save(publishedExpo(channel.getId()));

        when(roundClient.finishedExpoIds(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(List.of(expo.getId()));

        scheduler.closeFinishedExpos();
        var firstClosedAt = expoRepository.findById(expo.getId()).orElseThrow().getClosedAt();

        scheduler.closeFinishedExpos();
        var secondClosedAt = expoRepository.findById(expo.getId()).orElseThrow().getClosedAt();

        assertThat(firstClosedAt).isEqualTo(secondClosedAt);
    }

    @Test
    @DisplayName("대상이 limit 보다 많으면 마지막 id 를 커서로 넘겨 끝까지 마감한다 - 앞쪽 limit 개만 반복되면 안 된다")
    void 커서로_끝까지_마감() {
        Channel channel = channelRepository.save(Channel.create(uniqueName(), uniqueUserId(), "desc"));
        Expo first = expoRepository.save(publishedExpo(channel.getId()));
        Expo second = expoRepository.save(publishedExpo(channel.getId()));
        Expo third = expoRepository.save(publishedExpo(channel.getId()));

        ExpoAutoCloseScheduler target = AopTestUtils.getTargetObject(scheduler);
        Object originalLimit = ReflectionTestUtils.getField(target, "limit");
        ReflectionTestUtils.setField(target, "limit", 2);
        try {
            when(roundClient.finishedExpoIds(any(Instant.class), eq(0L), anyInt()))
                    .thenReturn(List.of(first.getId(), second.getId()));
            when(roundClient.finishedExpoIds(any(Instant.class), eq(second.getId()), anyInt()))
                    .thenReturn(List.of(third.getId()));

            scheduler.closeFinishedExpos();
        } finally {
            ReflectionTestUtils.setField(target, "limit", originalLimit);
        }

        assertThat(expoRepository.findById(third.getId()).orElseThrow().getStatus()).isEqualTo(ExpoStatus.CLOSED);
    }

    @Test
    @DisplayName("커서를 무시하고 같은 페이지만 돌아와도 무한 루프에 빠지지 않는다 - 옛 reservation-service 와 섞인 배포")
    void 커서가_전진하지_않으면_중단() {
        Channel channel = channelRepository.save(Channel.create(uniqueName(), uniqueUserId(), "desc"));
        Expo first = expoRepository.save(publishedExpo(channel.getId()));
        Expo second = expoRepository.save(publishedExpo(channel.getId()));

        ExpoAutoCloseScheduler target = AopTestUtils.getTargetObject(scheduler);
        Object originalLimit = ReflectionTestUtils.getField(target, "limit");
        ReflectionTestUtils.setField(target, "limit", 2);
        try {
            when(roundClient.finishedExpoIds(any(Instant.class), anyLong(), anyInt()))
                    .thenReturn(List.of(first.getId(), second.getId()));

            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                    () -> scheduler.closeFinishedExpos());
        } finally {
            ReflectionTestUtils.setField(target, "limit", originalLimit);
        }

        assertThat(expoRepository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(ExpoStatus.CLOSED);
    }

    @Test
    @DisplayName("finishedExpoIds 호출 실패 시 스케줄러가 예외를 던지지 않고 건너뛴다")
    void 호출_실패_시_건너뜀() {
        when(roundClient.finishedExpoIds(any(Instant.class), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("connection timeout"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> scheduler.closeFinishedExpos());
    }

    private Expo publishedExpo(Long channelId) {
        Expo expo = Expo.create(channelId, "테스트 박람회", null, null, "서울", "IT·전자", null);
        expo.publish();
        return expo;
    }

    private Expo hiddenExpo(Long channelId) {
        return Expo.create(channelId, "히든 박람회", null, null, "서울", "IT·전자", null);
    }
}
