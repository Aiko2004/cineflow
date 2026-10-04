package com.cineflow.booking.client;

import com.cineflow.booking.client.dto.SeatFeignResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// theater-service — обычный Feign (по конвенции; gRPC только для screening).
@FeignClient(name = "theater-service")
public interface SeatClient {

    @GetMapping("/api/seats/{id}")
    SeatFeignResponse getSeatById(@PathVariable String id);
}
