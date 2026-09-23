package ar.edu.utn.frba.arbiter.cases.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Null when no analysis ran (Fast Track, or no image attachments), preserved so the analyst UI
 * reads "no analysis" instead of an empty report.
 */
@Converter
public class ImageForensicReportJsonConverter implements AttributeConverter<ImageForensicReport, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ImageForensicReport attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize forensic report to JSON", e);
        }
    }

    @Override
    public ImageForensicReport convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, ImageForensicReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not read forensic report JSON", e);
        }
    }
}
