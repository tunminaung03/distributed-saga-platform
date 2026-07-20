package dev.tunmin.saga.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import dev.tunmin.saga.user.grpc.AuthRequest;
import dev.tunmin.saga.user.grpc.AuthResponse;
import dev.tunmin.saga.user.grpc.UserServiceGrpc;
import dev.tunmin.saga.userservice.UserServiceApplication;
import dev.tunmin.saga.userservice.domain.User;
import dev.tunmin.saga.userservice.infra.grpc.UserGrpcServiceImpl;
import dev.tunmin.saga.userservice.repository.UserRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

@SpringBootTest(classes = UserServiceApplication.class)
@Import(UserGrpcServiceImpl.class)
class UserGrpcServiceImplTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserGrpcServiceImpl userGrpcService;

    @Value("${app.jwt.secret:9f8c3b7e2d6a4f1c9b0e7d5a3c2f1e6b7a8c9d0e1f2a3b4c5d6e7f8091a2b3c4}")
    private String jwtSecret;

    @Value("${app.jwt.issuer:saga-ecosystem-auth}")
    private String jwtIssuer;

    private Server inProcessServer;
    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub blockingStub;
    
    private String hashedTestPassword;
    
    @BeforeEach
    void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        inProcessServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(userGrpcService)
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        blockingStub = UserServiceGrpc.newBlockingStub(channel);
        
        // Fast BCrypt setup for test executions
        this.hashedTestPassword = BCrypt.hashpw("securePassword123", BCrypt.gensalt(4));
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        inProcessServer.shutdownNow();
        userRepository.deleteAll();
    }

    /**
     * Golden Path Test: Verifies that when a valid active user provides matching password credentials,
     * the gRPC server generates a signed token containing the correct metadata parameters and Bearer type.
     */
    @Test
    void authenticateUser_ShouldReturnAuthResponseWithToken_WhenCredentialsAreValid() {
        // Arrange
        User user = User.builder()
                .id(1L)
                .email("alex@example.com")
                .passwordHash(hashedTestPassword)
                .active(true)
                .membership("GOLD")
                .build();
        userRepository.save(user);

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("alex@example.com")
                .setPassword("securePassword123")
                .build();

        // Act
        AuthResponse response = blockingStub.authenticateUser(request);

        // Assert: Verify the outer gRPC payload parameters match your protobuf contract
        assertNotNull(response);
        assertNotNull(response.getToken());
        assertEquals("Bearer", response.getTokenType());
        assertTrue(response.getExpiresIn() > 0);

        // Deep In-Memory Token Claim Verification: Simulates exactly what the API Gateway does
        Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
        JWTVerifier verifier = JWT.require(algorithm).withIssuer(jwtIssuer).build();
        DecodedJWT decodedJwt = verifier.verify(response.getToken());

        assertEquals("alex@example.com", decodedJwt.getSubject());
        assertEquals("1", decodedJwt.getClaim("user-id").asString());
        assertEquals("GOLD", decodedJwt.getClaim("role").asString());
    }
    
    /**
     * Edge Condition Scenario: Verifies that if a user record has a missing/null membership tier status,
     * the system handles it gracefully during claim generation without throwing NullPointerExceptions.
     */
    @Test
    void authenticateUser_ShouldHandleNullMembershipGracefully() {
        // Arrange
        User user = User.builder()
                .id(3L)
                .email("no.membership@example.com")
                .passwordHash(hashedTestPassword)
                .active(true)
                .membership(null) // Database field is null
                .build();
        userRepository.save(user);

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("no.membership@example.com")
                .setPassword("securePassword123")
                .build();

        // Act
        AuthResponse response = blockingStub.authenticateUser(request);

        // Assert
        assertNotNull(response);
        
        Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
        JWTVerifier verifier = JWT.require(algorithm).withIssuer(jwtIssuer).build();
        DecodedJWT decodedJwt = verifier.verify(response.getToken());

        assertEquals("no.membership@example.com", decodedJwt.getSubject());
        assertEquals("3", decodedJwt.getClaim("user-id").asString());
        
        // Jackson/JWT handles absent or null values as missing/null claims cleanly
        assertTrue(decodedJwt.getClaim("role").isNull() || decodedJwt.getClaim("role").asString() == null);
    }        
}
