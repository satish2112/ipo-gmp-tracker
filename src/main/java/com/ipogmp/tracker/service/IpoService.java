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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core business logic layer.
 * All reads serve data FROM MongoDB (GmpDataService decides when to write).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoService {

    private final IpoRepository       ipoRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // ─── READ (always from MongoDB) ───────────────────────────────────

    public List<IpoDTO> getAllIpos() {
        return ipoRepository.findAllByOrderByGmpDesc()
            .stream().map(IpoDTO::fromIpo).collect(Collectors.toList());
    }

    public IpoDTO getIpoById(String id) {
        return ipoRepository.findById(id)
            .map(IpoDTO::fromIpo)
            .orElseThrow(() -> new IpoNotFoundException("IPO not found: " + id));
    }

    public List<IpoDTO> getIposByStatus(Ipo.IpoStatus status) {
        return ipoRepository.findByStatus(status)
            .stream().map(IpoDTO::fromIpo).collect(Collectors.toList());
    }

    public List<IpoDTO> getActiveIpos() {
        return ipoRepository.findByStatusIn(List.of(Ipo.IpoStatus.OPEN, Ipo.IpoStatus.UPCOMING))
            .stream().map(IpoDTO::fromIpo).collect(Collectors.toList());
    }

    // ─── CREATE ───────────────────────────────────────────────────────

    public IpoDTO createIpo(IpoDTO dto) {
        if (ipoRepository.existsByNameIgnoreCase(dto.getName()))
            throw new DuplicateIpoException("IPO already exists: " + dto.getName());

        Ipo ipo = dto.toIpo();
        ipo.setLastUpdated(LocalDateTime.now());
        ipo.setGmpRecordedDate(LocalDate.now());
        ipo.setDailyOpenGmp(ipo.getGmp());
        ipo.setPreviousGmp(ipo.getGmp());

        Ipo saved = ipoRepository.save(ipo);
        log.info("Created IPO: {}", saved.getName());
        IpoDTO result = IpoDTO.fromIpo(saved);
        broadcastSingleUpdate("IPO_CREATED", saved);
        return result;
    }

    // ─── UPDATE ───────────────────────────────────────────────────────

    public IpoDTO updateIpo(String id, IpoDTO dto) {
        Ipo existing = ipoRepository.findById(id)
            .orElseThrow(() -> new IpoNotFoundException("IPO not found: " + id));

        ipoRepository.findByNameIgnoreCase(dto.getName())
            .filter(f -> !f.getId().equals(id))
            .ifPresent(f -> { throw new DuplicateIpoException("Name taken: " + dto.getName()); });

        Double oldGmp = existing.getGmp();
        existing.setName(dto.getName());
        existing.setGmp(dto.getGmp());
        existing.setPreviousGmp(oldGmp);
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
        existing.setLastUpdated(LocalDateTime.now());

        Ipo saved = ipoRepository.save(existing);
        IpoDTO result = IpoDTO.fromIpo(saved);
        broadcastSingleUpdate("IPO_UPDATED", saved);
        return result;
    }

    // ─── DELETE ───────────────────────────────────────────────────────

    public void deleteIpo(String id) {
        if (!ipoRepository.existsById(id))
            throw new IpoNotFoundException("IPO not found: " + id);
        ipoRepository.deleteById(id);
        messagingTemplate.convertAndSend("/topic/ipos",
            Map.of("event", "IPO_DELETED", "id", id));
    }

    // ─── WEBSOCKET BROADCAST ──────────────────────────────────────────

    /** Push full list snapshot to all WebSocket subscribers */
    public void broadcastAllIpos() {
        List<IpoDTO> all = getAllIpos();
        messagingTemplate.convertAndSend("/topic/ipos",
            Map.of("event", "ALL_IPOS", "data", all));
        log.debug("Broadcast {} IPOs", all.size());
    }

    /** Push a single IPO update event */
    public void broadcastSingleUpdate(String event, Ipo ipo) {
        messagingTemplate.convertAndSend("/topic/ipos",
            Map.of("event", event, "data", IpoDTO.fromIpo(ipo)));
    }
}
