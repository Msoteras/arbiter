package ar.edu.utn.frba.arbiter.classification.models.entities;

import ar.edu.utn.frba.arbiter.common.dto.ImageForensicReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists the structured {@link ImageForensicReport} as JSON in the classification_log row,
 * so the analyst UI can render the forensic section from the audit record. Null when no
 * analysis ran (Fast Track, isolated test, or a claim with no images).
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
            throw new IllegalArgumentException("Could not deserialize forensic report from JSON", e);
        }
    }
}
