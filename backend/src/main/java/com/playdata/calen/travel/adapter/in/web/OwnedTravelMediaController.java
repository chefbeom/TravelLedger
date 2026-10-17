package com.playdata.calen.travel.adapter.in.web;

import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.adapter.support.TravelMediaUploadMapper;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.dto.*;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/travel")
public class OwnedTravelMediaController {
    private final ManageOwnedTravelMediaUseCase media;
    private final TravelMediaHttpResponses responses;

    @PostMapping("/records/{recordId}/media/presign")
    public TravelMediaUploadPrepareResponse prepareRecord(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long recordId,
            @Valid @RequestBody TravelMediaUploadPrepareRequest request) {
        return TravelMediaUploadMapper.response(media.prepare(user.userId(), recordId, TravelRecordType.LEDGER,
                TravelMediaUploadMapper.candidates(request)));
    }

    @PostMapping("/memories/{memoryId}/media/presign")
    public TravelMediaUploadPrepareResponse prepareMemory(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long memoryId,
            @Valid @RequestBody TravelMediaUploadPrepareRequest request) {
        return TravelMediaUploadMapper.response(media.prepare(user.userId(), memoryId, TravelRecordType.MEMORY,
                TravelMediaUploadMapper.candidates(request)));
    }

    @PostMapping("/records/{recordId}/media/complete")
    public List<TravelMediaResponse> completeRecord(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long recordId,
            @Valid @RequestBody TravelMediaUploadCompleteRequest request) {
        return TravelMediaUploadMapper.responses(media.complete(user.userId(), recordId, TravelRecordType.LEDGER,
                request.mediaType(), request.caption(), TravelMediaUploadMapper.completed(request)));
    }

    @PostMapping("/memories/{memoryId}/media/complete")
    public List<TravelMediaResponse> completeMemory(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long memoryId,
            @Valid @RequestBody TravelMediaUploadCompleteRequest request) {
        return TravelMediaUploadMapper.responses(media.complete(user.userId(), memoryId, TravelRecordType.MEMORY,
                request.mediaType(), request.caption(), TravelMediaUploadMapper.completed(request)));
    }

    @DeleteMapping("/media/{mediaId}")
    public void delete(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long mediaId) {
        media.delete(user.userId(), mediaId);
    }

    @GetMapping("/media/{mediaId}/content")
    public ResponseEntity<?> download(@AuthenticationPrincipal AppUserPrincipal user, @PathVariable Long mediaId,
            @RequestParam(name = "thumbnail", defaultValue = "false") boolean thumbnail,
            @RequestParam(name = "w", required = false) Integer width) {
        return responses.render(media.download(user.userId(), mediaId), thumbnail, width);
    }
}
