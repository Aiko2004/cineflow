package com.cineflow.screening.grpc;

import com.cineflow.contracts.grpc.screening.GetScreeningRequest;
import com.cineflow.contracts.grpc.screening.GetScreeningResponse;
import com.cineflow.contracts.grpc.screening.HallSnapshot;
import com.cineflow.contracts.grpc.screening.MovieSnapshot;
import com.cineflow.contracts.grpc.screening.ScreeningServiceGrpc;
import com.cineflow.contracts.grpc.screening.SeatTypePrice;
import com.cineflow.contracts.grpc.screening.TheaterSnapshot;
import com.cineflow.screening.document.Screening;
import com.cineflow.screening.repository.ScreeningRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// gRPC-реализация ScreeningService (контракт — contracts/src/main/proto/screening.proto).
//
// Отличие от REST-контроллера на уровне кода:
//   REST: @GetMapping-метод возвращает объект, Spring сам сериализует в JSON.
//   gRPC: наследуем сгенерированный ImplBase, метод НИЧЕГО не возвращает —
//         результат отдаём через StreamObserver (onNext + onCompleted), а ошибку —
//         onError со Status. Это готово к стримингу (несколько onNext), хотя здесь unary.
//
// Один вызов GetScreening отдаёт и факт существования сеанса, и данные для денормализации
// брони (снапшоты + цены по типам мест) — booking-service не делает несколько REST-запросов.
@Component
@Slf4j
@RequiredArgsConstructor
public class ScreeningGrpcService extends ScreeningServiceGrpc.ScreeningServiceImplBase {

    private final ScreeningRepository screeningRepository;

    @Override
    public void getScreening(GetScreeningRequest request,
                             StreamObserver<GetScreeningResponse> responseObserver) {
        String id = request.getScreeningId();
        Screening s = screeningRepository.findById(id).orElse(null);
        if (s == null) {
            // Аналог REST 404: код NOT_FOUND, клиент увидит StatusRuntimeException с этим статусом.
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Screening not found: " + id)
                    .asRuntimeException());
            return;
        }

        GetScreeningResponse.Builder response = GetScreeningResponse.newBuilder()
                .setId(s.getId())
                .setStartAt(s.getStartAt().toString())
                .setEndAt(s.getEndAt().toString())
                .setScreeningDate(s.getScreeningDate().toString())
                .setMovie(MovieSnapshot.newBuilder()
                        .setId(nz(s.getMovie().id()))
                        .setTitle(nz(s.getMovie().title()))
                        .setSlug(nz(s.getMovie().slug()))
                        .setBanner(nz(s.getMovie().banner()))
                        .build())
                .setTheater(TheaterSnapshot.newBuilder()
                        .setId(nz(s.getTheater().id()))
                        .setName(nz(s.getTheater().name()))
                        .setAddress(nz(s.getTheater().address()))
                        .build())
                .setHall(HallSnapshot.newBuilder()
                        .setId(nz(s.getHall().id()))
                        .setName(nz(s.getHall().name()))
                        .build());

        s.getSeatTypes().forEach(st -> response.addSeatTypes(SeatTypePrice.newBuilder()
                .setType(st.type().name())
                .setPrice(st.price().toPlainString())
                .build()));

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    // proto3 string не допускает null — пустые снапшот-поля (slug/banner) отдаём как "".
    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
