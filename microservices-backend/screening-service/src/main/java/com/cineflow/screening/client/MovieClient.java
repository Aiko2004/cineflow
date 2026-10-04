package com.cineflow.screening.client;

import com.cineflow.screening.client.dto.MovieFeignResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "movie-service")
public interface MovieClient {

    // Path variable — String, хотя movie-service ожидает Long.
    // HTTP-путь — всегда строка; Spring MVC на принимающей стороне сам приведёт "1" → Long.
    // Это избавляет сервис от Long.parseLong() и отражает реальную природу HTTP.
    @GetMapping("/movies/{id}")
    MovieFeignResponse getById(@PathVariable String id);
}
