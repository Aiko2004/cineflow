package com.cineflow.screening.client;

import com.cineflow.screening.client.dto.HallFeignResponse;
import com.cineflow.screening.client.dto.TheaterFeignResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "theater-service")
public interface TheaterClient {

    @GetMapping("/api/theaters/{id}")
    TheaterFeignResponse getTheaterById(@PathVariable String id);

    @GetMapping("/api/halls/{id}")
    HallFeignResponse getHallById(@PathVariable String id);
}
