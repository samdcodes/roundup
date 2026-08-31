package com.roundup.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.roundup.model.api.RoundUpRequest;
import com.roundup.model.api.RoundUpResult;
import com.roundup.service.RoundUpService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/round-ups")
public class RoundUpController {

    private final RoundUpService roundUpService;

    public RoundUpController(RoundUpService roundUpService) {
        this.roundUpService = roundUpService;
    }

    @PostMapping
    public ResponseEntity<RoundUpResult> create(@Valid @RequestBody RoundUpRequest request) {
        return ResponseEntity.ok(roundUpService.roundup(request.startDate(), request.transferUid()));
    }
}
