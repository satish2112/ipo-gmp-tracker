package com.ipogmp.tracker.service;

import com.ipogmp.tracker.dto.IpoDTO;
import com.ipogmp.tracker.exception.DuplicateIpoException;
import com.ipogmp.tracker.exception.IpoNotFoundException;
import com.ipogmp.tracker.model.Ipo;
import com.ipogmp.tracker.repository.IpoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Core business logic for IPO GMP management.
 * Handles CRUD operations and broadcasts real-time updates via WebSocket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoService {

    private final IpoRepository ipoRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // ─── READ ────────────────────────────────────────────────────────────────

    public List<IpoDTO> getAllIpos() {
        return ipoRepository.findAllByOrderByGmpDesc()
                .stream()
                .map(IpoDTO::fromIpo)
                .collect(Collectors.toList());
    }

    public IpoDTO getIpoById(String id) {
        return ipoRepository.findById(id)
                .map(IpoDTO::fromIpo)
                .orElseThrow(() -> new IpoNotFoundException("IPO not found with id: " + id));
    }

    public List<IpoDTO> getIposByStatus(Ipo.IpoStatus status) {
        return ipoRepository.findByStatus(status)
                .stream()
                .map(IpoDTO::fromIpo)
                .collect(Collectors.toList());
    }

    public List<IpoDTO> getActiveIpos() {
        return ipoRepository.findByStatusIn(List.of(Ipo.IpoStatus.OPEN, Ipo.IpoStatus.UPCOMING))
                .stream()
                .map(IpoDTO::fromIpo)
                .collect(Collectors.toList());
    }

    // ─── CREATE ──────────────────────────────────────────────────────────────

    public IpoDTO createIpo(IpoDTO dto) {
        if (ipoRepository.existsByNameIgnoreCase(dto.getName())) {
            throw new DuplicateIpoException("IPO already exists with name: " + dto.getName());
        }

        Ipo ipo = dto.toIpo();
        ipo.setLastUpdated(LocalDateTime.now());
        Ipo saved = ipoRepository.save(ipo);
        log.info("Created IPO: {} with GMP: {}", saved.getName(), saved.getGmp());

        IpoDTO result = IpoDTO.fromIpo(saved);
        broadcastUpdate("IPO_CREATED", result);
        return result;
    }

    // ─── UPDATE ──────────────────────────────────────────────────────────────

    public IpoDTO updateIpo(String id, IpoDTO dto) {
        Ipo existing = ipoRepository.findById(id)
                .orElseThrow(() -> new IpoNotFoundException("IPO not found with id: " + id));

        // Check name uniqueness (excluding self)
        ipoRepository.findByNameIgnoreCase(dto.getName())
                .filter(found -> !found.getId().equals(id))
                .ifPresent(found -> {
                    throw new DuplicateIpoException("Another IPO already has the name: " + dto.getName());
                });

        // Preserve previous GMP for trend indicator
        Double previousGmp = existing.getGmp();

        existing.setName(dto.getName());
        existing.setGmp(dto.getGmp());
        existing.setKostakRate(dto.getKostakRate());
        existing.setSubjectToSauda(dto.getSubjectToSauda());
        existing.setIssuePrice(dto.getIssuePrice());
        existing.setOpenDate(dto.getOpenDate());
        existing.setCloseDate(dto.getCloseDate());
        existing.setListingDate(dto.getListingDate());
        existing.setLotSize(dto.getLotSize());
        existing.setIssueSize(dto.getIssueSize());
        existing.setRegistrar(dto.getRegistrar());
        if (dto.getStatus() != null) existing.setStatus(dto.getStatus());
        existing.setPreviousGmp(previousGmp);
        existing.setLastUpdated(LocalDateTime.now());

        Ipo saved = ipoRepository.save(existing);
        log.info("Updated IPO: {} — GMP changed: {} → {}", saved.getName(), previousGmp, saved.getGmp());

        IpoDTO result = IpoDTO.fromIpo(saved);
        broadcastUpdate("IPO_UPDATED", result);
        return result;
    }

    /**
     * Lightweight GMP-only update — called by the scheduler for bulk refreshes.
     */
    public IpoDTO updateGmp(String id, Double newGmp) {
        Ipo ipo = ipoRepository.findById(id)
                .orElseThrow(() -> new IpoNotFoundException("IPO not found with id: " + id));

        ipo.setPreviousGmp(ipo.getGmp());
        ipo.setGmp(newGmp);
        ipo.setLastUpdated(LocalDateTime.now());

        Ipo saved = ipoRepository.save(ipo);
        IpoDTO result = IpoDTO.fromIpo(saved);
        broadcastUpdate("GMP_UPDATED", result);
        return result;
    }

    // ─── DELETE ──────────────────────────────────────────────────────────────

    public void deleteIpo(String id) {
        if (!ipoRepository.existsById(id)) {
            throw new IpoNotFoundException("IPO not found with id: " + id);
        }
        ipoRepository.deleteById(id);
        log.info("Deleted IPO with id: {}", id);
        messagingTemplate.convertAndSend("/topic/ipos",
                java.util.Map.of("event", "IPO_DELETED", "id", id));
    }

    // ─── WEBSOCKET BROADCAST ─────────────────────────────────────────────────

    /**
     * Push a named event + payload to all connected WebSocket clients.
     */
    public void broadcastAllIpos() {
        List<IpoDTO> all = getAllIpos();
        messagingTemplate.convertAndSend("/topic/ipos",
                java.util.Map.of("event", "ALL_IPOS", "data", all));
        log.debug("Broadcast {} IPOs to WebSocket clients", all.size());
    }

    private void broadcastUpdate(String event, IpoDTO dto) {
        messagingTemplate.convertAndSend("/topic/ipos",
                java.util.Map.of("event", event, "data", dto));
    }
}
