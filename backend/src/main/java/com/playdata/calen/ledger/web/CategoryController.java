package com.playdata.calen.ledger.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.dto.CategoryDetailRequest;
import com.playdata.calen.ledger.dto.CategoryDetailResponse;
import com.playdata.calen.ledger.dto.CategoryGroupRequest;
import com.playdata.calen.ledger.dto.CategoryGroupResponse;
import com.playdata.calen.ledger.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public List<CategoryGroupResponse> getCategories(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) EntryType entryType,
            @RequestParam(defaultValue = "false") boolean includeInactive
    ) {
        return categoryService.getCategories(currentUser.userId(), entryType, includeInactive);
    }

    @PostMapping("/groups")
    public CategoryGroupResponse createGroup(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody CategoryGroupRequest request
    ) {
        return categoryService.createGroup(currentUser.userId(), request);
    }

    @PostMapping("/details")
    public CategoryDetailResponse createDetail(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody CategoryDetailRequest request
    ) {
        return categoryService.createDetail(currentUser.userId(), request);
    }

    @DeleteMapping("/groups/{id}")
    public void deactivateGroup(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        categoryService.deactivateGroup(currentUser.userId(), id);
    }

    @PatchMapping("/groups/{id}/activate")
    public CategoryGroupResponse activateGroup(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        return categoryService.activateGroup(currentUser.userId(), id);
    }

    @DeleteMapping("/details/{id}")
    public void deactivateDetail(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        categoryService.deactivateDetail(currentUser.userId(), id);
    }

    @PatchMapping("/details/{id}/activate")
    public CategoryDetailResponse activateDetail(@AuthenticationPrincipal AppUserPrincipal currentUser, @PathVariable Long id) {
        return categoryService.activateDetail(currentUser.userId(), id);
    }
}
