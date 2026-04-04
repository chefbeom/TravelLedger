package com.playdata.calen.travel.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.exif.GpsDirectory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class TravelPhotoGpsMetadataService {

    public ExtractedPhotoGps extract(MultipartFile file, String contentType) {
        if (file == null || file.isEmpty() || !isImageContentType(contentType)) {
            return null;
        }

        try (InputStream inputStream = file.getInputStream()) {
            return extract(inputStream);
        } catch (Exception exception) {
            log.debug("Failed to extract EXIF GPS from uploaded multipart image.", exception);
            return null;
        }
    }

    public ExtractedPhotoGps extract(Resource resource, String contentType) {
        if (resource == null || !isImageContentType(contentType)) {
            return null;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            return extract(inputStream);
        } catch (Exception exception) {
            log.debug("Failed to extract EXIF GPS from stored image resource.", exception);
            return null;
        }
    }

    private ExtractedPhotoGps extract(InputStream inputStream) {
        try {
            GpsDirectory gpsDirectory = ImageMetadataReader.readMetadata(inputStream)
                    .getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDirectory == null) {
                return null;
            }

            GeoLocation geoLocation = gpsDirectory.getGeoLocation();
            if (geoLocation == null
                    || Double.isNaN(geoLocation.getLatitude())
                    || Double.isNaN(geoLocation.getLongitude())) {
                return null;
            }

            return new ExtractedPhotoGps(
                    BigDecimal.valueOf(geoLocation.getLatitude()).setScale(7, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(geoLocation.getLongitude()).setScale(7, RoundingMode.HALF_UP)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isImageContentType(String contentType) {
        return StringUtils.hasText(contentType) && contentType.startsWith("image/");
    }

    public record ExtractedPhotoGps(
            BigDecimal latitude,
            BigDecimal longitude
    ) {
    }
}
