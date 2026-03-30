package com.playdata.calen.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "app.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:support-inquiry-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.support.attachment-storage-path=build/support-inquiries-test"
})
@AutoConfigureMockMvc
class SupportInquiryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void userCanSendInquiryAndAdminCanArchiveRestoreReplyAndDelete() throws Exception {
        MockHttpSession userSession = login("hana", "test1234", "12345678");
        MockHttpSession adminSession = login("admin", "test1234", "12345678");

        MockMultipartFile attachment = new MockMultipartFile(
                "attachment",
                "support.png",
                MediaType.IMAGE_PNG_VALUE,
                "fake-image".getBytes()
        );

        mockMvc.perform(multipart("/api/support/inquiries")
                        .file(attachment)
                        .param("title", "앱 문의")
                        .param("content", "관리자에게 전달할 테스트 문의입니다.")
                        .session(userSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("앱 문의"))
                .andExpect(jsonPath("$.attachmentUrl").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.archived").value(false));

        MvcResult inboxResult = mockMvc.perform(get("/api/admin/support-inquiries").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("앱 문의"))
                .andReturn();

        JsonNode inbox = objectMapper.readTree(inboxResult.getResponse().getContentAsString());
        long inquiryId = inbox.get(0).get("id").asLong();
        assertThat(inquiryId).isPositive();

        mockMvc.perform(get("/api/support/inquiries/{inquiryId}/attachment", inquiryId).session(userSession))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/support-inquiries/{inquiryId}/reply", inquiryId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "확인했고 다음 배포 때 검토하겠습니다."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.archived").value(true))
                .andExpect(jsonPath("$.replyContent").value("확인했고 다음 배포 때 검토하겠습니다."));

        mockMvc.perform(patch("/api/admin/support-inquiries/{inquiryId}/archive", inquiryId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("archived", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(false));

        mockMvc.perform(patch("/api/admin/support-inquiries/{inquiryId}/archive", inquiryId)
                        .session(adminSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("archived", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(delete("/api/admin/support-inquiries/{inquiryId}", inquiryId)
                        .session(adminSession)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/support/inquiries/me").session(userSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].replyContent").value("확인했고 다음 배포 때 검토하겠습니다."))
                .andExpect(jsonPath("$[0].attachmentUrl").isNotEmpty());

        mockMvc.perform(get("/api/admin/support-inquiries").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private MockHttpSession login(String loginId, String password, String secondaryPin) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "loginId", loginId,
                                "password", password,
                                "secondaryPin", secondaryPin,
                                "rememberDevice", false
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
