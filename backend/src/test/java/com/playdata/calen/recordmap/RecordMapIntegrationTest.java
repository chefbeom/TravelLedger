package com.playdata.calen.recordmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.recordmap.repository.RecordMapShareRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import java.time.LocalDateTime;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
    "app.seed.enabled=true", "app.seed.allow-insecure-default-credentials=true",
    "spring.datasource.url=jdbc:h2:mem:record-map-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false", "app.schema.legacy-updaters.enabled=false",
    "minio.endpoint=", "minio.access-key=", "minio.secret-key=", "app.travel.presigned-upload-enabled=false",
    "app.travel.media-storage-path=build/record-map-test-media"
})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RecordMapIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserRepository users;
    @Autowired RecordMapShareRepository shares;
    @Autowired TravelExpenseRecordRepository travelRecords;

    @Test void membershipDeletionPreservesVisitsAndVisitStateIsDerived() throws Exception {
        long a = collection("hana", "food");
        long b = collection("hana", "dates");
        long p = place("hana", List.of(a, b));
        long v = visit("hana", p, "2026-10-10");
        visit("hana", p, "2026-10-13");
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId", ""+a).param("state","VISITED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.places[0].visitCount").value(2));
        mvc.perform(delete("/api/record-map/collections/{id}", a).with(as("hana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId", ""+b))
            .andExpect(status().isOk()).andExpect(jsonPath("$.places[0].visitCount").value(2)).andExpect(jsonPath("$.visits.length()").value(2));
        mvc.perform(delete("/api/record-map/places/{id}", p).with(as("hana")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/record-map/visits/{id}", v).with(as("hana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId", ""+b).param("from","2026-10-12").param("to","2026-10-13"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.visits.length()").value(1)).andExpect(jsonPath("$.visits[0].date").value("2026-10-13"));
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId", ""+b).param("state","WANTED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.places.length()").value(0));
    }

    @Test void authenticationCsrfAndOwnerScopesAreEnforced() throws Exception {
        long c = collection("hana", "private");
        long p = place("hana", List.of(c));
        long v = visit("hana", p, "2026-10-13");
        mvc.perform(get("/api/record-map/overview")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/record-map/places").with(as("hana")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(placeBody(List.of()))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/record-map/places/{id}/visits", p).with(as("minsu"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/record-map/visits/{id}", v).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/record-map/collections/{id}", c).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/record-map/places").with(as("minsu")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(placeBody(List.of(c)))))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("state","invalid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/record-map/places").with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"bad point\",\"latitude\":91,\"longitude\":127}")).andExpect(status().isBadRequest());
    }

    @Test void collectionShareDoesNotExposeOtherPhotosAndRevocationStopsAllReads() throws Exception {
        long c = collection("hana", "shared");
        long p = place("hana", List.of(c));
        long v = visit("hana", p, "2026-10-13");
        long photo = upload("hana", v);
        long privatePhoto = upload("hana", visit("hana", place("hana", List.of()), "2026-10-13"));
        mvc.perform(get("/api/record-map/photos/{id}/content", photo).with(as("minsu"))).andExpect(status().isNotFound());
        String privateToken = postJson("hana", "/api/record-map/collections/"+c+"/shares", Map.of("includeVisits",false,"expiresInDays",7)).path("token").asText();
        mvc.perform(get("/api/record-map/public/{token}", privateToken)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.visits.length()").value(0))
            .andExpect(jsonPath("$.collections[0].memo").doesNotExist()).andExpect(jsonPath("$.places[0].memo").doesNotExist());
        mvc.perform(get("/api/record-map/public/{token}", privateToken).param("from","2026-10-13")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/record-map/public/{token}/photos/{id}/content", privateToken, photo)).andExpect(status().isNotFound());
        JsonNode shared = postJson("hana", "/api/record-map/collections/"+c+"/shares", Map.of("includeVisits",true,"expiresInDays",7));
        String token = shared.path("token").asText();
        mvc.perform(get("/api/record-map/public/{token}", token)).andExpect(status().isOk()).andExpect(jsonPath("$.visits.length()").value(1));
        mvc.perform(get("/api/record-map/public/{token}/photos/{id}/content", token, photo)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/record-map/public/{token}/photos/{id}/content", token, privatePhoto)).andExpect(status().isNotFound());
        mvc.perform(get("/api/record-map/collections/{id}/shares", c).with(as("hana")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].token").doesNotExist());
        mvc.perform(delete("/api/record-map/shares/{id}", shared.path("id").asLong()).with(as("minsu")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/record-map/shares/{id}", shared.path("id").asLong()).with(as("hana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/record-map/public/{token}", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/record-map/public/{token}/photos/{id}/content", token, photo)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/record-map/collections/{id}", c).with(as("hana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/record-map/photos/{id}/content", photo).with(as("hana"))).andExpect(status().isOk());
    }

    @Test void travelImportReferencesOwnedOriginalAndKeepsTravelUnchanged() throws Exception {
        long plan = postJson("hana","/api/travel/plans", Map.of("name","original","destination","Japan","startDate","2026-10-10",
            "endDate","2026-10-13","homeCurrency","KRW","headCount",1,"status","COMPLETED","colorHex","#009E8C","memo","original")).path("id").asLong();
        Map<String,Object> memory = new LinkedHashMap<>();
        memory.put("memoryDate","2026-10-13"); memory.put("memoryTime","12:30"); memory.put("category","장소");
        memory.put("title","travel cafe"); memory.put("country","Japan"); memory.put("region","Osaka"); memory.put("placeName","Cafe");
        memory.put("latitude",34.5); memory.put("longitude",135.5); memory.put("memo","original diary");
        long record = postJson("hana", "/api/travel/plans/"+plan+"/memories", memory).path("id").asLong();
        long c = collection("hana","travel finds");
        long imported = postJson("hana","/api/record-map/travel-imports",Map.of("recordId",record,"collectionIds",List.of(c))).path("id").asLong();
        assertThat(postJson("hana","/api/record-map/travel-imports",Map.of("recordId",record,"collectionIds",List.of(c))).path("id").asLong()).isEqualTo(imported);
        mvc.perform(post("/api/record-map/travel-imports").with(as("minsu")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("recordId",record,"collectionIds",List.of())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId",""+c))
            .andExpect(status().isOk()).andExpect(jsonPath("$.visits[0].travelPlanId").value(plan)).andExpect(jsonPath("$.visits[0].memo").value("original diary"));
        travelRecords.deleteById(record);
        mvc.perform(get("/api/record-map/overview").with(as("hana")).param("collectionId",""+c).param("state","WANTED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.places[0].visited").value(false)).andExpect(jsonPath("$.visits.length()").value(0));
        mvc.perform(delete("/api/record-map/places/{id}", imported).with(as("hana")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/travel/plans/{id}", plan).with(as("hana"))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("original"));
    }

    @Test void expiryAndRemovingCollectionMembershipStopPreviouslySharedPhotoAccess() throws Exception {
        long c = collection("hana", "expiry");
        long p = place("hana", List.of(c));
        long photo = upload("hana", visit("hana", p, "2026-10-13"));
        JsonNode link = postJson("hana", "/api/record-map/collections/"+c+"/shares", Map.of("includeVisits",true,"expiresInDays",7));
        String token = link.path("token").asText();
        mvc.perform(put("/api/record-map/places/{id}",p).with(as("hana")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(placeBody(List.of())))).andExpect(status().isOk());
        mvc.perform(get("/api/record-map/public/{token}", token)).andExpect(status().isOk()).andExpect(jsonPath("$.places.length()").value(0));
        mvc.perform(get("/api/record-map/public/{token}/photos/{id}/content",token,photo)).andExpect(status().isNotFound());
        var stored = shares.findById(link.path("id").asLong()).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(1)); shares.saveAndFlush(stored);
        mvc.perform(get("/api/record-map/public/{token}",token)).andExpect(status().isNotFound());
    }

    @Test void disguisedImageIsRejectedWithoutCreatingAnyPhotoRow() throws Exception {
        long p = place("hana", List.of());
        long v = visit("hana", p, "2026-10-13");
        mvc.perform(multipart("/api/record-map/visits/{id}/photos",v)
            .file(new MockMultipartFile("file","fake.png","image/png","not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .with(as("hana")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/record-map/places/{id}/visits",p).with(as("hana")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].photos.length()").value(0));
    }

    @Test void photoLimitIsEnforcedBeforeAnExtraFileIsStored() throws Exception {
        long p = place("hana", List.of());
        long v = visit("hana", p, "2026-10-13");
        for (int i = 0; i < 20; i++) upload("hana", v);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB), "png", out);
        mvc.perform(multipart("/api/record-map/visits/{id}/photos",v)
            .file(new MockMultipartFile("file","extra.png","image/png",out.toByteArray()))
            .with(as("hana")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/record-map/places/{id}/visits",p).with(as("hana")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].photos.length()").value(20));
    }

    private RequestPostProcessor as(String login) { return user(AppUserPrincipal.from(users.findByLoginId(login).orElseThrow())); }
    private JsonNode postJson(String login, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).with(as(login)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private long collection(String login, String name) throws Exception {
        return postJson(login,"/api/record-map/collections",Map.of("name",name,"memo","private collection memo","colorHex","#009E8C")).path("id").asLong();
    }
    private Map<String,Object> placeBody(List<Long> collectionIds) {
        return Map.of("name","Cafe","memo","place note","latitude",37.55,"longitude",126.97,"visited",false,"collectionIds",collectionIds);
    }
    private long place(String login, List<Long> ids) throws Exception { return postJson(login,"/api/record-map/places",placeBody(ids)).path("id").asLong(); }
    private long visit(String login, long place, String date) throws Exception {
        return postJson(login,"/api/record-map/places/"+place+"/visits",Map.of("title","visit","memo","private visit","date",date)).path("id").asLong();
    }
    private long upload(String login, long visit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",out);
        JsonNode result = json.readTree(mvc.perform(multipart("/api/record-map/visits/{id}/photos",visit)
            .file(new MockMultipartFile("file","test.png","image/png",out.toByteArray())).with(as(login)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return Long.parseLong(result.path("id").asText().split(":")[1]);
    }
}
