package com.playdata.calen.ledger.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImageContent;
import com.playdata.calen.ledger.ocr.LedgerOcrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

class LedgerOcrControllerTest {
    private final LedgerOcrService service=mock(LedgerOcrService.class);
    private final LedgerOcrController controller=new LedgerOcrController(service);
    private final AppUserPrincipal user=new AppUserPrincipal(7L,"fixture-user","Fixture","",AppUserRole.USER,true);

    @Test void storedImageStillReturnsInlineBytesUtf8NameAndTheCurrentOwnerId() {
        byte[] bytes={1,2,3}; when(service.getHistoryImage(7L,11L)).thenReturn(new StoredImageContent(bytes,"image/jpeg","영수증 사진.jpg"));
        var response=controller.getHistoryImage(user,11L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(response.getBody()).isSameAs(bytes);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("영수증 사진.jpg");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("UTF-8");
        verify(service).getHistoryImage(7L,11L);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"","not/a/type invalid"})
    void missingOrInvalidStoredMimeStillUsesTheExistingOctetStreamFallback(String mime) {
        when(service.getHistoryImage(7L,11L)).thenReturn(new StoredImageContent(new byte[]{1},mime,"receipt.jpg"));
        assertThat(controller.getHistoryImage(user,11L).getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }
    @Test void bothExistingHttpAliasesAndImagePathArePreserved() throws Exception {
        assertThat(LedgerOcrController.class.getAnnotation(RequestMapping.class).path()).containsExactly("/api/ledger/ocr","/api/ledger/image-analysis");
        var method=LedgerOcrController.class.getDeclaredMethod("getHistoryImage",AppUserPrincipal.class,Long.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/history/{historyId}/image");
    }
}
