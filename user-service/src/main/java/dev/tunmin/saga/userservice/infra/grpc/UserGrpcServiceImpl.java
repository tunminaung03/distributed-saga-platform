package dev.tunmin.saga.userservice.infra.grpc;

import java.util.Date;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.server.service.GrpcService;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import dev.tunmin.saga.user.grpc.AuthRequest;
import dev.tunmin.saga.user.grpc.AuthResponse;
import dev.tunmin.saga.user.grpc.UserServiceGrpc.UserServiceImplBase;
import dev.tunmin.saga.userservice.domain.User;
import dev.tunmin.saga.userservice.exception.InvalidCredentialsException;
import dev.tunmin.saga.userservice.exception.UserInactiveException;
import dev.tunmin.saga.userservice.exception.UserNotFoundException;
import dev.tunmin.saga.userservice.repository.UserRepository;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

@GrpcService
@Slf4j
public class UserGrpcServiceImpl extends UserServiceImplBase {
	
	
	private final UserRepository userRepository;
    private final String secret;
    private final String issuer;
    private final long expirationMs;	    
	
	public UserGrpcServiceImpl(
            UserRepository userRepository,
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
		
        this.userRepository = userRepository;
        this.secret = secret;
        this.issuer = issuer;
        this.expirationMs = expirationMs;
    }		
	
	@Override
	public void authenticateUser(AuthRequest authRequest, StreamObserver<AuthResponse> responseObserver) {
		
		log.info("Authenticating user credentials for email: {}", 
					authRequest.getEmail());
		
		GrpcExecutor.execute(responseObserver, () -> {
			
			User user = userRepository.findByEmail(authRequest.getEmail())
							.orElseThrow(() -> 
								new UserNotFoundException("User identity not found for input target: " 
															+ authRequest.getEmail()));
			
			if (!Boolean.TRUE.equals(user.getActive())) {
				
				throw new UserInactiveException("User profile is locked or inactive for target: " 
								+ authRequest.getEmail());
			}
			
			if (!BCrypt.checkpw(authRequest.getPassword(), user.getPasswordHash())) {
				
				throw new InvalidCredentialsException("Cryptographic credential check failed for target: " 
								+ authRequest.getEmail());
			}
			
			Algorithm algorithm = Algorithm.HMAC256(secret);            
            
            String token = JWT.create()
                    .withIssuer(issuer)
                    .withSubject(user.getEmail())
                    .withClaim("user-id", String.valueOf(user.getId())) // Injected context
                    .withClaim("role", user.getMembership())
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + expirationMs))
                    .sign(algorithm);

            return AuthResponse.newBuilder()
            			.setToken(token)
            			.setTokenType("Bearer")
            			.setExpiresIn(expirationMs / 1000)	// Response as seconds
            			.build();
				
		});
	}		
}
