package dev.tunmin.saga.infrastructure.grpc;

public class TestPasswordGen {
	public static void main(String[] args) {
		// Paste this line temporarily inside your Spring Boot application startup block
		System.out.println("VALID_LOCAL_HASH: " + org.mindrot.jbcrypt.BCrypt.hashpw("securePassword123", org.mindrot.jbcrypt.BCrypt.gensalt()));
		// Paste this line temporarily inside your Spring Boot application startup block		
		
		
//SET search_path TO user_service_schema;
//UPDATE users SET password_hash = '$2a$10$zN0o8WPKUvKmyBoeCGJTQeUlex3YyzV.3eLDmQF6W5l7BHuJhkQJm';

// user-1		
//{"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoiZGV2ZWxvcGVyQHR1bm1pbi5kZXYiLCJ1c2VyLWlkIjoiMSIsInJvbGUiOiJQUkVNSVVNIiwiaWF0IjoxNzgyNzEyMjYxLCJleHAiOjE3ODI3MTU4NjF9.YnOHD0cNUBXiTqARyW7oAcEBLe2wfuQiL-emCWWQO2M","type":"Bearer","expiresIn":3600}

// user-2
// {"token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoidXNlckB0dW5taW4uZGV2IiwidXNlci1pZCI6IjIiLCJyb2xlIjoiU1RBTkRBUkQiLCJpYXQiOjE3ODI3MTMyMjEsImV4cCI6MTc4MjcxNjgyMX0.-wnZXqie2AkbsC6ov9Z-LPJdWIGfv6pjiFc9gNYkOF4","type":"Bearer","expiresIn":3600}
		
// User ID 1 - PREMIUM
// curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"developer@tunmin.dev\",\"password\": \"securePassword123\"}"
// User ID 2 - STANDARD
// curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"user@tunmin.dev\",\"password\": \"securePassword123\"}"
// User ID 3 - INACTIVE
// curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"suspended@tunmin.dev\",\"password\": \"securePassword123\"}"
		
		
		
		// happy flow
// curl -X POST http://localhost:8080/orders -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoiZGV2ZWxvcGVyQHR1bm1pbi5kZXYiLCJ1c2VyLWlkIjoiMSIsInJvbGUiOiJQUkVNSVVNIiwiaWF0IjoxNzgzNTM5ODM2LCJleHAiOjE3ODM1NDM0MzZ9.Xqvp-qn5GNjzrNKVtqQOEPTtU7kiWBYjkIsOagrPejA" -H "Content-Type: application/json" -d "{\"customerId\": \"1\", \"totalAmount\": 15.99, \"itemCode\": \"PIZZA_01\", \"quantity\": 2}"

		//=====userId mismatch
// curl -X POST http://localhost:8080/orders -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoiZGV2ZWxvcGVyQHR1bm1pbi5kZXYiLCJ1c2VyLWlkIjoiMSIsInJvbGUiOiJQUkVNSVVNIiwiaWF0IjoxNzgyODg1MTA1LCJleHAiOjE3ODI4ODg3MDV9.pxqWkKVlGA5p0WEP1HD4eNFV0UueOihw_Br0yhKjn08" -H "Content-Type: application/json" -d "{\"customerId\": \"2\", \"totalAmount\": 15.99, \"itemCode\": \"PIZZA_01\", \"quantity\": 2}"
		
		//=====low stock
// curl -X POST http://localhost:8080/orders -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoidXNlckB0dW5taW4uZGV2IiwidXNlci1pZCI6IjIiLCJyb2xlIjoiU1RBTkRBUkQiLCJpYXQiOjE3ODI4ODUyNTAsImV4cCI6MTc4Mjg4ODg1MH0.kSV2j2cX9pu7zE-RGe3Lfz0etJGCdeeatiS4seAc_ac" -H "Content-Type: application/json" -d "{\"customerId\": \"2\", \"totalAmount\": 45.00, \"itemCode\": \"STEAK_02\", \"quantity\": 2}"

		//====saga compensation
// curl -X POST http://localhost:8080/orders -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoiZGV2ZWxvcGVyQHR1bm1pbi5kZXYiLCJ1c2VyLWlkIjoiMSIsInJvbGUiOiJQUkVNSVVNIiwiaWF0IjoxNzgzMzYxMDkyLCJleHAiOjE3ODMzNjQ2OTJ9.sfM8Z1qqyTMidBTh6-Bsk7_tGRkQ-0IOrCowkFP_koE" -H "Content-Type: application/json" -d "{\"customerId\": \"1\", \"totalAmount\": 8.50, \"itemCode\": \"TACO_02\", \"quantity\": 1}"

		//Hibernate Validation Framework Boundary Interception (Missing customer values, negative monetary amounts, or invalid product quantities)
// curl -X POST http://localhost:8080/orders -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzYWdhLWVjb3N5c3RlbS1hdXRoIiwic3ViIjoiZGV2ZWxvcGVyQHR1bm1pbi5kZXYiLCJ1c2VyLWlkIjoiMSIsInJvbGUiOiJQUkVNSVVNIiwiaWF0IjoxNzgzMDExMDY2LCJleHAiOjE3ODMwMTQ2NjZ9.88Xp7Ngw3Uq6-49cqnwenuDZEs9Zu9Q3y7tYvHIOaSU" -H "Content-Type: application/json" -d "{\"customerId\": \"\", \"totalAmount\": -5.00, \"itemCode\": \"BURGER_01\", \"quantity\": 0}"
		
		

		


	}
}
