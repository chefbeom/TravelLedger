package com.playdata.calen.sharing.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.sharing.adapter.support.RecordShareContractMapper;
import com.playdata.calen.sharing.domain.model.ShareCreationData.LedgerSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Preserves the existing persisted/wire JSON, including HH:mm formatting. */
@Component @RequiredArgsConstructor
public class ShareSnapshotCodec {
    private final ObjectMapper mapper;

    public String encode(LedgerSnapshot snapshot) {
        if (snapshot == null) return null;
        try { return mapper.writeValueAsString(RecordShareContractMapper.ledgerResponse(snapshot)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to prepare record share."); }
    }

    public LedgerSnapshot decode(String json) {
        if (json == null) return null;
        try { return RecordShareContractMapper.snapshot(mapper.readValue(json, LedgerEntryResponse.class)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read shared record."); }
    }

    public String fingerprint(String json) {
        if (json == null) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
