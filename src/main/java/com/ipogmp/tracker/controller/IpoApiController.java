package com.ipogmp.tracker.controller;

import com.ipogmp.tracker.dto.ApiResponse;
import com.ipogmp.tracker.dto.IpoDTO;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.service.IpoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API Controller — exposes CRUD endpoints for IPO data.
 * Base path: /api/ipos
 */
@Slf4j
@RestController
@RequestMapping("/api/ipos")
@RequiredArgsConstructor
public class IpoApiController {

    private final IpoService ipoService;

    /**
     * GET /api/ipos
     * Returns all IPOs ordered by GMP descending.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<IpoDTO>>> getAllIpos(
            @RequestParam(required = false) String status) {

        List<IpoDTO> ipos;
        if (status != null) {
            try {
                Ipo.IpoStatus ipoStatus = Ipo.IpoStatus.valueOf(status.toUpperCase());
                ipos = ipoService.getIposByStatus(ipoStatus);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Invalid status value: " + status));
            }
        } else {
            ipos = ipoService.getAllIpos();
        }

        return ResponseEntity.ok(ApiResponse.success(ipos));
    }

    /**
     * GET /api/ipos/active
     * Returns only OPEN and UPCOMING IPOs.
     */
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<IpoDTO>>> getActiveIpos() {
        return ResponseEntity.ok(ApiResponse.success(ipoService.getActiveIpos()));
    }

    /**
     * GET /api/ipos/{id}
     * Returns a single IPO by MongoDB ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<IpoDTO>> getIpoById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(ipoService.getIpoById(id)));
    }

    /**
     * POST /api/ipos
     * Creates a new IPO entry. Requires ADMIN role.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<IpoDTO>> createIpo(@Valid @RequestBody IpoDTO dto) {
        IpoDTO created = ipoService.createIpo(dto);
        log.info("API: Created IPO '{}'", created.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("IPO created successfully", created));
    }

    /**
     * PUT /api/ipos/{id}
     * Updates an existing IPO. Requires ADMIN role.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<IpoDTO>> updateIpo(
            @PathVariable String id,
            @Valid @RequestBody IpoDTO dto) {
        IpoDTO updated = ipoService.updateIpo(id, dto);
        return ResponseEntity.ok(ApiResponse.success("IPO updated successfully", updated));
    }

    /**
     * PATCH /api/ipos/{id}/gmp
     * Lightweight GMP-only update (used by external integrations / scrapers).
     */
    @PatchMapping("/{id}/gmp")
    public ResponseEntity<ApiResponse<IpoDTO>> updateGmp(
            @PathVariable String id,
            @RequestParam Double value) {
        IpoDTO updated = ipoService.updateGmp(id, value);
        return ResponseEntity.ok(ApiResponse.success("GMP updated", updated));
    }

    /**
     * DELETE /api/ipos/{id}
     * Deletes an IPO entry. Requires ADMIN role.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteIpo(@PathVariable String id) {
        ipoService.deleteIpo(id);
        return ResponseEntity.ok(ApiResponse.success("IPO deleted successfully", null));
    }
}
