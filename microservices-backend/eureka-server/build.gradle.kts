plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "eureka-server"

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}
