rootProject.name = "microservices-backend"

include(
    "eureka-server",
    "api-gateway",
    "movie-service",
    "theater-service",
    "screening-service",
    "auth-service",
    "booking-service",
//    "payment-service",
//    "users-service",
//    "notification-service",
    "contracts"
)