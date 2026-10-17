package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelRecordMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelRecordCommandController {
    private final WriteTravelRecordUseCase records;
    @PostMapping("/plans/{planId}/records")
    public TravelExpenseRecordResponse createExpense(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId, @Valid @RequestBody TravelExpenseRecordRequest body) {
        return TravelRecordMapper.expense(records.createExpense(user.userId(), planId, TravelRecordMapper.draft(body)));
    }
    @PutMapping("/records/{recordId}")
    public TravelExpenseRecordResponse updateExpense(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long recordId, @Valid @RequestBody TravelExpenseRecordRequest body) {
        return TravelRecordMapper.expense(records.updateExpense(user.userId(), recordId, TravelRecordMapper.draft(body)));
    }
    @DeleteMapping("/records/{recordId}")
    public void deleteExpense(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long recordId) { records.deleteExpense(user.userId(), recordId); }
    @PostMapping("/plans/{planId}/memories")
    public TravelMemoryRecordResponse createMemory(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long planId, @Valid @RequestBody TravelMemoryRecordRequest body) {
        return TravelRecordMapper.memory(records.createMemory(user.userId(), planId, TravelRecordMapper.draft(body)));
    }
    @PutMapping("/memories/{memoryId}")
    public TravelMemoryRecordResponse updateMemory(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long memoryId, @Valid @RequestBody TravelMemoryRecordRequest body) {
        return TravelRecordMapper.memory(records.updateMemory(user.userId(), memoryId, TravelRecordMapper.draft(body)));
    }
    @DeleteMapping("/memories/{memoryId}")
    public void deleteMemory(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long memoryId) { records.deleteMemory(user.userId(), memoryId); }
}
