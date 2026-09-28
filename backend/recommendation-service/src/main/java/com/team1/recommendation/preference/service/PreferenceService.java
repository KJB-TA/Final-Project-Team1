package com.team1.recommendation.preference.service;

import com.team1.recommendation.preference.dto.PreferencesResponse;
import com.team1.recommendation.preference.dto.UpsertPreferencesRequest;
import com.team1.recommendation.preference.entity.PreferenceType;
import com.team1.recommendation.preference.entity.UserPreference;
import com.team1.recommendation.preference.repository.UserPreferenceRepository;
import com.team1.recommendation.score.service.PreferenceScoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PreferenceService {

    private final UserPreferenceRepository repository;
    private final PreferenceScoreService preferenceScoreService;

    public PreferenceService(UserPreferenceRepository repository,
                             PreferenceScoreService preferenceScoreService) {
        this.repository = repository;
        this.preferenceScoreService = preferenceScoreService;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(Long userId) {
        List<UserPreference> prefs = repository.findByUserId(userId);
        List<String> categories = prefs.stream()
                .filter(p -> PreferenceType.CATEGORY.equals(p.getType()))
                .map(UserPreference::getValue).toList();
        List<String> keywords = prefs.stream()
                .filter(p -> PreferenceType.KEYWORD.equals(p.getType()))
                .map(UserPreference::getValue).toList();
        return new PreferencesResponse(categories, keywords);
    }

    @Transactional
    public PreferencesResponse upsert(Long userId, UpsertPreferencesRequest request) {
        repository.deleteAllByUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        // 한 요청 안의 중복도 UNIQUE(user_id, type, value) 에 걸리므로 미리 걸러낸다.
        List<String> categories = normalize(request.categories());
        List<String> keywords = normalize(request.keywords());

        categories.forEach(c ->
                repository.save(UserPreference.of(userId, PreferenceType.CATEGORY, c, now)));
        keywords.forEach(k ->
                repository.save(UserPreference.of(userId, PreferenceType.KEYWORD, k, now)));

        preferenceScoreService.applyInterests(userId, categories, keywords);

        return new PreferencesResponse(categories, keywords);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
