package dev.tunmin.saga.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import dev.tunmin.saga.userservice.infra.grpc.UserGrpcServiceImpl;
import dev.tunmin.saga.user.grpc.AuthRequest;
import dev.tunmin.saga.user.grpc.UserServiceGrpc;
import dev.tunmin.saga.userservice.UserServiceApplication;
import dev.tunmin.saga.userservice.domain.User;
import dev.tunmin.saga.userservice.repository.UserRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

@SpringBootTest(classes = UserServiceApplication.class)
@Import(UserGrpcServiceImpl.class)
public class UserGrpcServiceImplErrorTest {
	
	@MockitoBean
    private UserRepository userRepository;

    @Autowired
    private UserGrpcServiceImpl userGrpcService;
    
    private Server inProcessServer;
    private ManagedChannel channel;
    private UserServiceGrpc.UserServiceBlockingStub blockingStub;
    
    /// initialize
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
        userRepository.deleteAll();
    }
    
    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        inProcessServer.shutdownNow();
    }
    
    /**
     * Scenario: Verifies that if a login email address does not exist inside the database,
     * the executor catches UserNotFoundException and accurately returns a gRPC NOT_FOUND status.
     */
    @Test
    void authenticateUser_ShouldReturnNotFoundStatus_WhenUserRecordIsMissing() {
    	
        when(userRepository.findByEmail(anyString()))
        	.thenReturn(Optional.empty());

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("missing@tunmin.dev")
                .setPassword("securePassword123")
                .build();

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.authenticateUser(request));

        assertEquals(Status.Code.NOT_FOUND, exception.getStatus().getCode());
    }

    /**
     * Scenario: Verifies that if a user record is found but the account's active flag is false,
     * the executor catches UserInactiveException and maps it to a gRPC PERMISSION_DENIED status.
     */
    @Test
    void authenticateUser_ShouldReturnPermissionDeniedStatus_WhenUserIsInactive() {
        User inactiveUser = User.builder()
                .id(3L)
                .email("suspended@tunmin.dev")
                .passwordHash("anyHashedPasswordString")
                .active(false) // Deactivated state
                .membership("STANDARD")
                .build();

        when(userRepository.findByEmail("suspended@tunmin.dev")).thenReturn(Optional.of(inactiveUser));

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("suspended@tunmin.dev")
                .setPassword("securePassword123")
                .build();

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.authenticateUser(request));

        assertEquals(Status.Code.PERMISSION_DENIED, exception.getStatus().getCode());
    }

    /**
     * Scenario: Verifies that if the user exists and is active, but provides an incorrect password,
     * the executor catches InvalidCredentialsException and maps it to a gRPC UNAUTHENTICATED status.
     */
    @Test
    void authenticateUser_ShouldReturnUnauthenticatedStatus_WhenPasswordCheckFails() {
        String correctPassword = "securePassword123";
        String hashed = BCrypt.hashpw(correctPassword, BCrypt.gensalt(4)); // Use low salt rounds for fast test execution

        User activeUser = User.builder()
                .id(1L)
                .email("developer@tunmin.dev")
                .passwordHash(hashed)
                .active(true)
                .build();

        when(userRepository.findByEmail("developer@tunmin.dev")).thenReturn(Optional.of(activeUser));

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("developer@tunmin.dev")
                .setPassword("wrongPlaintextPassword") // Fails BCrypt check
                .build();

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.authenticateUser(request));

        assertEquals(Status.Code.UNAUTHENTICATED, exception.getStatus().getCode());
    }

    /**
     * Scenario: Verifies that generic, unhandled system runtime exceptions are intercepted 
     * by the fallback handler block and converted cleanly into standard gRPC INTERNAL statuses.
     */
    @Test
    void authenticateUser_ShouldReturnInternalStatus_WhenRepositoryThrowsGenericException() {
        when(userRepository.findByEmail(anyString()))
                .thenThrow(new RuntimeException("Database cluster connectivity loss"));

        AuthRequest request = AuthRequest.newBuilder()
                .setEmail("developer@tunmin.dev")
                .setPassword("securePassword123")
                .build();

        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> blockingStub.authenticateUser(request));

        assertEquals(Status.Code.INTERNAL, exception.getStatus().getCode());
        assertEquals("An unexpected internal service error occurred.", exception.getStatus().getDescription());
    }
}
