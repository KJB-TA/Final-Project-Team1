package com.team1.expo.support;

import com.team1.expo.client.ReservationClient;
import com.team1.expo.client.TicketClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test"
)
public abstract class IntegrationTestSupport {

    @MockBean
    protected ReservationClient reservationClient;

    @MockBean
    protected TicketClient ticketClient;

    protected static final String TEST_JWT_SECRET =
            "test-secret-0123456789abcdef0123456789abcdef0123456789abcdef";
    protected static final String TEST_INTERNAL_TOKEN = "test-internal-token";

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0").withDatabaseName("expo");

    static {
        MYSQL.start();
    }

    // Container 가 기동 시각으로 만드는 자체 서명 인증서가 호스트 시계보다 앞서면 핸드셰이크가 깨진다
    private static String jdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        return base + (base.contains("?") ? "&" : "?")
                + "sslMode=DISABLED&allowPublicKeyRetrieval=true";
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestSupport::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("internal.token", () -> TEST_INTERNAL_TOKEN);
    }

    protected static String jwtFor(long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes()))
                .compact();
    }

    protected static String uniqueName() {
        return "channel-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(1000L);

    protected static long uniqueUserId() {
        return USER_ID_SEQ.getAndIncrement();
    }
}
