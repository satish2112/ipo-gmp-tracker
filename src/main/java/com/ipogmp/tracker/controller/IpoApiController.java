package com.ipogmp.tracker.controller;

import com.ipogmp.tracker.dto.ApiResponse;
import com.ipogmp.tracker.dto.IpoDTO;
import com.ipogmp.tracker.model.GmpHistory;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.GmpHistoryRepository;
import com.ipogmp.tracker.service.IpoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST API — CRUD for IPOs + GMP history endpoint.
 */
@Slf4j
@RestController
@RequestMapping("/api/ipos")
@RequiredArgsConstructor
public class IpoApiController {

    private final IpoService           ipoService;
    private final GmpHistoryRepository historyRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<IpoDTO>>> getAllIpos(
            @RequestParam(required = false) String status) {
        List<IpoDTO> ipos = (status != null)
            ? ipoService.getIposByStatus(Ipo.IpoStatus.valueOf(status.toUpperCase()))
            : ipoService.getAllIpos();
        return ResponseEntity.ok(ApiResponse.success(ipos));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<IpoDTO>>> getActiveIpos() {
        return ResponseEntity.ok(ApiResponse.success(ipoService.getActiveIpos()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<IpoDTO>> getIpoById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(ipoService.getIpoById(id)));
    }

    /**
     * GET /api/ipos/{id}/history?days=7
     * Returns GMP history entries for the detail-panel chart.
     * days param: how many past calendar days to include (default 7, max 30).
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<GmpHistory>>> getHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.min(Math.max(days, 1), 30);
        LocalDate since = LocalDate.now().minusDays(safeDays - 1);
        List<GmpHistory> history =
            historyRepository.findByIpoIdAndTradeDateGreaterThanEqualOrderByRecordedAtAsc(id, since);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IpoDTO>> createIpo(@Valid @RequestBody IpoDTO dto) {
        IpoDTO created = ipoService.createIpo(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success("IPO created", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<IpoDTO>> updateIpo(
            @PathVariable String id, @Valid @RequestBody IpoDTO dto) {
        return ResponseEntity.ok(ApiResponse.success("Updated", ipoService.updateIpo(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteIpo(@PathVariable String id) {
        ipoService.deleteIpo(id);
        return ResponseEntity.ok(ApiResponse.success("Deleted", null));
    }
}
