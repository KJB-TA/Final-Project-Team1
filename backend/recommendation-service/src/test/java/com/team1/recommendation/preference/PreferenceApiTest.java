package com.team1.recommendation.preference;

import com.team1.recommendation.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreferenceApiTest extends ApiTestSupport {

    @Test
    void upsertInterests_성공() throws Exception {
        mockMvc.perform(put("/api/v1/me/interests")
                        .header("Authorization", "Bearer " + USER_JWT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["IT", "패션"],
                                  "keywords": ["AI", "스타트업"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.categories[0]").value("IT"))
                .andExpect(jsonPath("$.data.keywords[0]").value("AI"));
    }

    @Test
    void upsertInterests_기존값을_유지한채_다시_저장해도_성공() throws Exception {
        // 삭제가 INSERT 보다 늦게 나가면 두 번째 저장이 UNIQUE 위반(500)으로 실패했다.
        String jwt = userJwt(9101L);
        putInterests(jwt, """
                {"categories": [], "keywords": ["자동차"]}
                """)
                .andExpect(status().isOk());

        putInterests(jwt, """
                {"categories": ["IT·전자"], "keywords": ["자동차", "AI"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0]").value("IT·전자"))
                .andExpect(jsonPath("$.data.keywords.length()").value(2));

        mockMvc.perform(get("/api/v1/me/interests").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.keywords.length()").value(2));
    }

    @Test
    void upsertInterests_한_요청안의_중복과_공백은_걸러낸다() throws Exception {
        putInterests(userJwt(9102L), """
                {"categories": ["IT·전자", "IT·전자"], "keywords": ["AI", " AI ", ""]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1))
                .andExpect(jsonPath("$.data.keywords.length()").value(1))
                .andExpect(jsonPath("$.data.keywords[0]").value("AI"));
    }

    private ResultActions putInterests(String jwt, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/me/interests")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void upsertInterests_인증없으면_401() throws Exception {
        mockMvc.perform(put("/api/v1/me/interests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categories": ["IT"], "keywords": []}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
